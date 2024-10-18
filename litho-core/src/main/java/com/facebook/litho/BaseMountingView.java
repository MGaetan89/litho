/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho;

import static com.facebook.litho.ThreadUtils.assertMainThread;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Preconditions;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.infer.annotation.ThreadConfined;
import com.facebook.litho.TreeState.TreeMountInfo;
import com.facebook.litho.animation.AnimatedProperties;
import com.facebook.litho.animation.AnimatedProperty;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.debug.DebugOverlay;
import com.facebook.litho.stats.LithoStats;
import com.facebook.rendercore.MountDelegateTarget;
import com.facebook.rendercore.MountState;
import com.facebook.rendercore.RenderCoreExtensionHost;
import com.facebook.rendercore.RenderTree;
import com.facebook.rendercore.RenderTreeUpdateListener;
import com.facebook.rendercore.extensions.ExtensionState;
import com.facebook.rendercore.extensions.RenderCoreExtension;
import com.facebook.rendercore.transitions.AnimatedRootHost;
import com.facebook.rendercore.visibility.VisibilityMountExtension;
import com.facebook.rendercore.visibility.VisibilityOutput;
import com.facebook.rendercore.visibility.VisibilityUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

@Nullsafe(Nullsafe.Mode.LOCAL)
public abstract class BaseMountingView extends ComponentHost
    implements RenderCoreExtensionHost, AnimatedRootHost {

  private static final String REENTRANT_MOUNTS_EXCEED_MAX_ATTEMPTS =
      "ComponentTree:ReentrantMountsExceedMaxAttempts";
  private static final int REENTRANT_MOUNTS_MAX_ATTEMPTS = 25;
  static final int SIZE_UNSET = -1;
  private static final String TAG = BaseMountingView.class.getSimpleName();
  private static final Rect EMPTY_RECT = new Rect();

  private @Nullable LifecycleOwner mLifecycleOwner;
  private final MountState mMountState;
  public final int mViewAttributeFlags;
  protected int mAnimatedWidth = SIZE_UNSET;
  protected int mAnimatedHeight = SIZE_UNSET;
  private @Nullable LithoHostListenerCoordinator mLithoHostListenerCoordinator;
  private boolean mIsMountStateDirty;
  private boolean mIsMounting;
  private @Nullable Deque<ReentrantMount> mReentrantMounts;
  private final Rect mPreviousMountVisibleRectBounds = new Rect();
  private int mTransientStateCount;
  private Boolean mHasTransientState = false;
  private boolean mHasVisibilityHint;
  private boolean mPauseMountingWhileVisibilityHintFalse;
  private boolean mVisibilityHintIsVisible;
  private boolean mSkipMountingIfNotVisible;
  private final Rect mRect = new Rect();
  private boolean mIsAttached;
  // This is a flag to indicate that the BaseMountingView is currently being detached temporarily,
  // like when disappearing transition happens we will detach the view and re-attach to its skip
  // root.
  protected boolean mIsTemporaryDetached;

  private @Nullable OnDirtyMountListener mOnDirtyMountListener = null;

  public BaseMountingView(Context context) {
    this(context, null);
  }

  public BaseMountingView(Context context, @Nullable AttributeSet attrs) {
    this(new ComponentContext(context), attrs);
  }

  public BaseMountingView(ComponentContext context) {
    this(context, null);
  }

  public BaseMountingView(ComponentContext context, @Nullable AttributeSet attrs) {
    super(context.getAndroidContext(), attrs, /* UnsafeModificationPolicy */ null);
    mMountState = new MountState(this, ComponentsSystrace.getSystrace());
    mMountState.setEnsureParentMounted(true);
    mViewAttributeFlags = LithoMountData.getViewAttributeFlags(this);
  }

  /**
   * Sets the width that the BaseMountingView should take on the next measure pass and then requests
   * a layout. This should be called from animation-driving code on each frame to animate the size
   * of the BaseMountingView.
   */
  @Override
  public void setAnimatedWidth(int width) {
    mAnimatedWidth = width;
    requestLayout();
  }

  @Override
  protected void performLayout(boolean changed, int left, int top, int right, int bottom) {
    final boolean isTracing = ComponentsSystrace.isTracing();
    try {
      if (isTracing) {
        ComponentsSystrace.beginSection("LithoView.performLayout");
      }
      if (hasTree()) {

        onBeforeLayout(left, top, right, bottom);

        boolean wasMountTriggered = mountComponentIfNeeded();

        // If this happens the LithoView might have moved on Screen without a scroll event
        // triggering incremental mount. We trigger one here to be sure all the content is visible.
        if (!wasMountTriggered) {
          notifyVisibleBoundsChanged();
        }

        if (!wasMountTriggered || shouldAlwaysLayoutChildren()) {
          // If the layout() call on the component didn't trigger a mount step,
          // we might need to perform an inner layout traversal on children that
          // requested it as certain complex child views (e.g. ViewPager,
          // RecyclerView, etc) rely on that.
          performLayoutOnChildrenIfNecessary(this);
        }
      }
    } finally {
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  private static void performLayoutOnChildrenIfNecessary(ComponentHost host) {
    final int childCount = host.getChildCount();
    if (childCount == 0) {
      return;
    }

    // Snapshot the children before traversal as measure/layout could trigger events which cause
    // children to be mounted/unmounted.
    View[] children = new View[childCount];
    for (int i = 0; i < childCount; i++) {
      children[i] = host.getChildAt(i);
    }

    for (int i = 0; i < childCount; i++) {
      final View child = children[i];
      if (child.getParent() != host) {
        // child has been removed
        continue;
      }

      if (child.isLayoutRequested()) {
        // The hosting view doesn't allow children to change sizes dynamically as
        // this would conflict with the component's own layout calculations.
        child.measure(
            MeasureSpec.makeMeasureSpec(child.getWidth(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(child.getHeight(), MeasureSpec.EXACTLY));
        child.layout(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
      }

      if (child instanceof ComponentHost) {
        performLayoutOnChildrenIfNecessary((ComponentHost) child);
      }
    }
  }

  /**
   * Indicates if the children of this view should be laid regardless to a mount step being
   * triggered on layout. This step can be important when some of the children in the hierarchy are
   * changed (e.g. resized) but the parent wasn't.
   *
   * <p>Since the framework doesn't expect its children to resize after being mounted, this should
   * be used only for extreme cases where the underline views are complex and need this behavior.
   *
   * @return boolean Returns true if the children of this view should be laid out even when a mount
   *     step was not needed.
   */
  protected boolean shouldAlwaysLayoutChildren() {
    return false;
  }

  protected void onBeforeLayout(int left, int top, int right, int bottom) {}

  @Nullable
  protected LifecycleOwner getLifecycleOwner() {
    return mLifecycleOwner;
  }

  protected abstract void onLifecycleOwnerChanged(
      @Nullable LifecycleOwner previousLifecycleOwner,
      @Nullable LifecycleOwner currentLifecycleOwner);

  private void attachLifecycleOwner() {
    LifecycleOwner lifecycleOwner = getDefaultLifecycleOwner(this);
    if (lifecycleOwner != null && mLifecycleOwner != lifecycleOwner) {
      LifecycleOwner previousLifecycleOwner = mLifecycleOwner;
      mLifecycleOwner = lifecycleOwner;
      onLifecycleOwnerChanged(previousLifecycleOwner, mLifecycleOwner);
    }
  }

  private void detachLifecycleOwner() {
    if (mLifecycleOwner != null) {
      LifecycleOwner previousLifecycleOwner = mLifecycleOwner;
      mLifecycleOwner = null;
      onLifecycleOwnerChanged(previousLifecycleOwner, null);
    }
  }

  @Nullable
  private static LifecycleOwner getDefaultLifecycleOwner(View view) {
    if (ComponentsConfiguration.defaultInstance.enableDefaultLifecycleOwnerAsFragmentOrActivity) {
      try {
        return FragmentManager.findFragment(view);
      } catch (IllegalStateException e) {
        return getLifecycleOwnerFromContext(view.getContext());
      }
    } else {
      return ViewTreeLifecycleOwner.get(view);
    }
  }

  @Nullable
  private static LifecycleOwner getLifecycleOwnerFromContext(Context context) {
    if (context instanceof LifecycleOwner) {
      return (LifecycleOwner) context;
    } else if (context instanceof ContextWrapper) {
      return getLifecycleOwnerFromContext(((ContextWrapper) context).getBaseContext());
    } else {
      return null;
    }
  }

  /**
   * Invoke this before the result of getCurrentLayoutState is about to change to a new non-null
   * tree.
   */
  void onBeforeSettingNewTree() {
    clearVisibilityItems();
    clearLastMountedTree();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    onAttach();
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    onDetach();
  }

  @Override
  public void onStartTemporaryDetach() {
    mIsTemporaryDetached = true;
    super.onStartTemporaryDetach();
    onDetach();
  }

  @Override
  public void onFinishTemporaryDetach() {
    mIsTemporaryDetached = false;
    super.onFinishTemporaryDetach();
    onAttach();
  }

  private void onAttach() {
    if (!mIsAttached) {
      mIsAttached = true;
      attachLifecycleOwner();
      onAttached();
    }
  }

  protected void onAttached() {
    mMountState.attach();
  }

  private void onDetach() {
    if (mIsAttached) {
      mIsAttached = false;
      onDetached();
      detachLifecycleOwner();
    }
  }

  public void rebind() {
    mMountState.attach();
  }

  /**
   * To be called this when the LithoView is about to become inactive. This means that either the
   * view is about to be recycled or moved off-screen.
   */
  public void unbind() {
    mMountState.detach();
  }

  protected void onDetached() {
    maybeUnmountComponents();
    mMountState.detach();
  }

  private void maybeUnmountComponents() {
    final @Nullable ComponentsConfiguration config = getConfiguration();
    if (config != null
        && config.enableFixForIM
        && !mIsTemporaryDetached
        && !hasTransientState()
        && isIncrementalMountEnabled()) {
      notifyVisibleBoundsChanged(EMPTY_RECT);
    }
  }

  boolean isAttached() {
    return mIsAttached;
  }

  /**
   * Sets the height that the BaseMountingView should take on the next measure pass and then
   * requests a layout. This should be called from animation-driving code on each frame to animate
   * the size of the BaseMountingView.
   */
  @Override
  public void setAnimatedHeight(int height) {
    mAnimatedHeight = height;
    requestLayout();
  }

  @Override
  public boolean hasTransientState() {
    if (ComponentsConfiguration.shouldOverrideHasTransientState) {
      return mHasTransientState;
    } else {
      return super.hasTransientState();
    }
  }

  @Override
  public void setHasTransientState(boolean hasTransientState) {
    super.setHasTransientState(hasTransientState);

    if (hasTransientState) {
      if (mTransientStateCount == 0 && hasTree()) {
        notifyVisibleBoundsChanged(new Rect(0, 0, getWidth(), getHeight()), false);
      }
      if (mTransientStateCount == 0) {
        mHasTransientState = true;
      }
      mTransientStateCount++;
    } else {
      mTransientStateCount--;
      if (mTransientStateCount == 0) {
        mHasTransientState = false;
      }
      if (mTransientStateCount == 0 && hasTree()) {
        // We mounted everything when the transient state was set on this view. We need to do this
        // partly to unmount content that is not visible but mostly to get the correct visibility
        // events to be fired.
        notifyVisibleBoundsChanged();
      }
      if (mTransientStateCount < 0) {
        mTransientStateCount = 0;
      }
    }
  }

  @Override
  public void notifyVisibleBoundsChanged(Rect visibleRect, boolean processVisibilityOutputs) {
    if (getCurrentLayoutState() == null) {
      return;
    }

    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("BaseMountingView.notifyVisibleBoundsChangedWithRect");
    }
    if (isIncrementalMountEnabled()) {
      mountComponent(visibleRect, processVisibilityOutputs);
    } else if (processVisibilityOutputs) {
      processVisibilityOutputs(visibleRect);
    }
    if (isTracing) {
      ComponentsSystrace.endSection();
    }
  }

  @UiThread
  void mountComponent(@Nullable Rect currentVisibleArea, boolean processVisibilityOutputs) {
    assertMainThread();

    if (mIsMounting) {
      collectReentrantMount(new ReentrantMount(currentVisibleArea, processVisibilityOutputs));
      return;
    }

    maybeMountOrNotifyVisibleBoundsChange(currentVisibleArea, processVisibilityOutputs);

    consumeReentrantMounts();
  }

  void setMountStateDirty() {
    mIsMountStateDirty = true;
    mPreviousMountVisibleRectBounds.setEmpty();
  }

  /** Deprecated: Consider subscribing the BaseMountingView to a LithoLifecycleOwner instead. */
  @Deprecated
  public void unmountAllItems() {
    mMountState.unmountAllItems();
    mLithoHostListenerCoordinator = null;
    mPreviousMountVisibleRectBounds.setEmpty();
  }

  public Rect getPreviousMountBounds() {
    return mPreviousMountVisibleRectBounds;
  }

  boolean mountStateNeedsRemount() {
    return mMountState.needsRemount();
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  public MountDelegateTarget getMountDelegateTarget() {
    return mMountState;
  }

  private void collectReentrantMount(ReentrantMount reentrantMount) {
    if (mReentrantMounts == null) {
      mReentrantMounts = new ArrayDeque<>();
    } else if (mReentrantMounts.size() > REENTRANT_MOUNTS_MAX_ATTEMPTS) {
      logReentrantMountsExceedMaxAttempts();
      mReentrantMounts.clear();
      return;
    }
    mReentrantMounts.add(reentrantMount);
  }

  private void consumeReentrantMounts() {
    if (mReentrantMounts != null) {
      final Deque<ReentrantMount> requests = new ArrayDeque<>(mReentrantMounts);
      mReentrantMounts.clear();

      while (!requests.isEmpty()) {
        final ReentrantMount request = Preconditions.checkNotNull(requests.pollFirst());
        setMountStateDirty();
        mount(request.currentVisibleArea, request.processVisibilityOutputs);
      }
    }
  }

  private void maybeMountOrNotifyVisibleBoundsChange(
      final @Nullable Rect actualVisibleRect, final boolean requestVisibilityEvents) {
    final LayoutState layoutState = getCurrentLayoutState();
    if (layoutState == null) {
      return;
    }

    if (shouldPauseMountingWithVisibilityHintFalse()) {
      return;
    }

    final Rect visibleRectToUse;
    final boolean processVisibilityOutputs;
    if (mTransientStateCount > 0 && hasTree() && isIncrementalMountEnabled()) {
      // If transient state is set but the MountState is dirty we want to re-mount everything.
      // Otherwise, we don't need to do anything as the entire BaseMountingView was mounted when
      // the transient state was set.
      if (!isMountStateDirty()) {
        return;
      } else {
        visibleRectToUse = new Rect(0, 0, getWidth(), getHeight());
        processVisibilityOutputs = false;
      }
    } else {
      visibleRectToUse = actualVisibleRect;
      processVisibilityOutputs = requestVisibilityEvents;
    }

    if (visibleRectToUse == null) {
      mPreviousMountVisibleRectBounds.setEmpty();
    } else {
      mPreviousMountVisibleRectBounds.set(visibleRectToUse);
    }

    final boolean needsMount = isMountStateDirty() || mountStateNeedsRemount();
    if (visibleRectToUse != null && !needsMount) {
      try {
        mIsMounting = true;
        layoutState.setShouldProcessVisibilityOutputs(processVisibilityOutputs);
        Preconditions.checkNotNull(mMountState.getMountDelegate())
            .notifyVisibleBoundsChanged(visibleRectToUse);
      } finally {
        mIsMounting = false;
      }
    } else {
      mount(visibleRectToUse, processVisibilityOutputs);
    }
  }

  private void mount(
      final @Nullable Rect visibleRect, final boolean shouldProcessVisibilityEvents) {

    final LayoutState layoutState = getCurrentLayoutState();
    if (layoutState == null) {
      return;
    }
    if (shouldPauseMountingWithVisibilityHintFalse()) {
      return;
    }

    final boolean isMountStateDirty = isMountStateDirty();
    try {

      // If this is the first mount, we need to set the hasMounted flag on the TreeState.
      final TreeMountInfo mountInfo = getMountInfo();
      if (mountInfo != null && !mountInfo.hasMounted) {
        mountInfo.isFirstMount = true;
        mountInfo.hasMounted = true;
      }

      mIsMounting = true;

      Object onBeforeMountResult = onBeforeMount();

      layoutState.setShouldProcessVisibilityOutputs(shouldProcessVisibilityEvents);

      final RenderTree renderTree = layoutState.toRenderTree();
      setupMountExtensions();
      Preconditions.checkNotNull(mLithoHostListenerCoordinator);
      mLithoHostListenerCoordinator.beforeMount(layoutState, visibleRect);
      mMountState.mount(renderTree);
      LithoStats.incrementComponentMountCount();
      drawDebugOverlay(this, layoutState.getComponentTreeId());

      onAfterMount(onBeforeMountResult);
      mIsMountStateDirty = false;

      final TreeState treeState = getTreeState();
      if (isMountStateDirty && treeState != null) {
        layoutState.recordRenderData();
        treeState.recordRenderData(layoutState);
      }
    } catch (Exception e) {
      throw ComponentUtils.wrapWithMetadata(this, e);
    } finally {
      if (getMountInfo() != null) {
        getMountInfo().isFirstMount = false;
      }
      mIsMounting = false;
      if (isMountStateDirty) {
        onDirtyMountComplete();
      }
    }
  }

  /**
   * Called before the mounting process actually happens on this view. It can return an Object that
   * will be passed in as param to onAfterMount
   *
   * @return an object that will be passed to onAfterMount
   */
  @Nullable
  Object onBeforeMount() {
    return null;
  }

  /**
   * Called right after the mountProcess is finished before resetting the dirtyMount flag.
   *
   * @param fromOnBeforeMount this is whatever was returned by the onBeforMount call. The default is
   *     null.
   */
  void onAfterMount(@Nullable Object fromOnBeforeMount) {}

  // We pause mounting while the visibility hint is set to false, because the visible rect of
  // the BaseMountingView is not consistent with what's currently on screen.
  private boolean shouldPauseMountingWithVisibilityHintFalse() {
    return mPauseMountingWhileVisibilityHintFalse
        && mHasVisibilityHint
        && !mVisibilityHintIsVisible;
  }

  /**
   * Dispatch a visibility events to all the components hosted in this BaseMountingView.
   *
   * <p>Marked as @Deprecated to indicate this method is experimental and should not be widely used.
   *
   * <p>NOTE: Can only be used when Incremental Mount is disabled! Call this method when the
   * BaseMountingView is considered eligible for the visibility event (i.e. only dispatch
   * VisibleEvent when the BaseMountingView is visible in its container).
   *
   * @param visibilityEventType The class type of the visibility event to dispatch. Supported:
   *     VisibleEvent.class, InvisibleEvent.class, FocusedVisibleEvent.class,
   *     UnfocusedVisibleEvent.class, FullImpressionVisibleEvent.class.
   */
  @Deprecated
  public void dispatchVisibilityEvent(Class<?> visibilityEventType) {
    if (isIncrementalMountEnabled()) {
      throw new IllegalStateException(
          "dispatchVisibilityEvent - "
              + "Can't manually trigger visibility events when incremental mount is enabled");
    }

    LayoutState layoutState = getCurrentLayoutState();

    if (layoutState != null && visibilityEventType != null) {
      for (int i = 0; i < layoutState.getVisibilityOutputCount(); i++) {
        dispatchVisibilityEvent(layoutState.getVisibilityOutputAt(i), visibilityEventType);
      }

      List<BaseMountingView> childViews = getChildMountingViewsFromCurrentlyMountedItems();
      for (BaseMountingView baseMountingView : childViews) {
        baseMountingView.dispatchVisibilityEvent(visibilityEventType);
      }
    }
  }

  @VisibleForTesting
  public List<BaseMountingView> getChildMountingViewsFromCurrentlyMountedItems() {
    return getChildMountingViewsFromCurrentlyMountedItems(mMountState);
  }

  private static List<BaseMountingView> getChildMountingViewsFromCurrentlyMountedItems(
      MountDelegateTarget mountDelegateTarget) {
    final ArrayList<BaseMountingView> childMountingViews = new ArrayList<>();

    for (int i = 0, size = mountDelegateTarget.getMountItemCount(); i < size; i++) {
      final Object content = mountDelegateTarget.getContentAt(i);
      if (content instanceof HasLithoViewChildren) {
        ((HasLithoViewChildren) content).obtainLithoViewChildren(childMountingViews);
      }
    }

    return childMountingViews;
  }

  private void dispatchVisibilityEvent(
      VisibilityOutput visibilityOutput, Class<?> visibilityEventType) {
    final Object content =
        visibilityOutput.hasMountableContent
            ? mMountState.getContentById(visibilityOutput.renderUnitId)
            : null;
    if (visibilityEventType == VisibleEvent.class) {
      if (visibilityOutput.getVisibleEventHandler() != null) {
        VisibilityUtils.dispatchOnVisible(visibilityOutput.getVisibleEventHandler(), content);
      }
    } else if (visibilityEventType == InvisibleEvent.class) {
      if (visibilityOutput.getInvisibleEventHandler() != null) {
        VisibilityUtils.dispatchOnInvisible(visibilityOutput.getInvisibleEventHandler());
      }
    } else if (visibilityEventType == FocusedVisibleEvent.class) {
      if (visibilityOutput.getFocusedEventHandler() != null) {
        VisibilityUtils.dispatchOnFocused(visibilityOutput.getFocusedEventHandler());
      }
    } else if (visibilityEventType == UnfocusedVisibleEvent.class) {
      if (visibilityOutput.getUnfocusedEventHandler() != null) {
        VisibilityUtils.dispatchOnUnfocused(visibilityOutput.getUnfocusedEventHandler());
      }
    } else if (visibilityEventType == FullImpressionVisibleEvent.class) {
      if (visibilityOutput.getFullImpressionEventHandler() != null) {
        VisibilityUtils.dispatchOnFullImpression(visibilityOutput.getFullImpressionEventHandler());
      }
    }
  }

  public synchronized void setOnDirtyMountListener(OnDirtyMountListener onDirtyMountListener) {
    mOnDirtyMountListener = onDirtyMountListener;
  }

  protected synchronized void onDirtyMountComplete() {
    if (mOnDirtyMountListener != null) {
      mOnDirtyMountListener.onDirtyMount(this);
    }
  }

  @Nullable
  protected TreeMountInfo getMountInfo() {
    final TreeState treeState = getTreeState();
    return treeState != null ? treeState.getMountInfo() : null;
  }

  /**
   * @return Whether the current Litho tree has been mounted at least once.
   */
  protected boolean hasMountedAtLeastOnce() {
    final TreeMountInfo mountInfo = getMountInfo();
    return mountInfo != null && mountInfo.hasMounted;
  }

  private void logReentrantMountsExceedMaxAttempts() {
    final String message =
        "Reentrant mounts exceed max attempts"
            + ", view="
            + LithoViewTestHelper.toDebugString(this)
            + ", component="
            + (hasTree() ? getTreeName() : null);
    ComponentsReporter.emitMessage(
        ComponentsReporter.LogLevel.FATAL, REENTRANT_MOUNTS_EXCEED_MAX_ATTEMPTS, message);
  }

  @Override
  public void notifyVisibleBoundsChanged() {
    if (getCurrentLayoutState() == null) {
      return;
    }

    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("BaseMountingView.notifyVisibleBoundsChanged");
    }

    notifyVisibleBoundsChanged(null);

    if (isTracing) {
      ComponentsSystrace.endSection();
    }
  }

  @Override
  public void onRegisterForPremount(@Nullable Long frameTime) {
    final @Nullable ComponentsConfiguration config = getConfiguration();
    if (config != null && config.useIncrementalMountGapWorker) {
      final boolean isTracing = ComponentsSystrace.isTracing();
      if (isTracing) {
        ComponentsSystrace.beginSection("BaseMountingView::onRegisterForPremount");
      }
      mount(new Rect(), false);
      RenderCoreExtension.onRegisterForPremount(mMountState, frameTime);
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  @Override
  public void onUnregisterForPremount() {
    final @Nullable ComponentsConfiguration config = getConfiguration();
    if (config != null && config.useIncrementalMountGapWorker) {
      final boolean isTracing = ComponentsSystrace.isTracing();
      if (isTracing) {
        ComponentsSystrace.beginSection("BaseMountingView::onUnregisterForPremount");
      }
      RenderCoreExtension.onUnregisterForPremount(mMountState);
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  @Override
  public void setRenderTreeUpdateListener(@Nullable RenderTreeUpdateListener listener) {
    mMountState.setRenderTreeUpdateListener(listener);
  }

  /**
   * If true, calling {@link #setVisibilityHint(boolean, boolean)} will delegate to {@link
   * #setVisibilityHint(boolean)} and skip mounting if the visibility hint was set to false. You
   * should not need this unless you don't have control over calling setVisibilityHint on the
   * BaseMountingView you own.
   */
  public void setSkipMountingIfNotVisible(boolean skipMountingIfNotVisible) {
    assertMainThread();
    mSkipMountingIfNotVisible = skipMountingIfNotVisible;
  }

  @UiThread
  void notifyVisibleBoundsChanged(@Nullable Rect rect) {
    assertMainThread();

    final @Nullable ComponentsConfiguration config = getConfiguration();
    final boolean mountWhenAttachAndDetach = (config != null) && config.enableFixForIM;
    if (!hasTree()
        || (mountWhenAttachAndDetach && Objects.equals(rect, mPreviousMountVisibleRectBounds))) {
      return;
    }

    final Rect visibleRectToUse;
    final boolean areBoundsVisible;
    if (rect == null) {
      // As [View.getLocalVisibleRect] on detach can return a value which cannot be for used for
      // processing visibility. For example, a visible Rect can be returned even when it's totally
      // invisible. This can cause components to not dispatch the correct visibility events
      // correctly or not get unmounted by incremental mount. We manually invokes the visible bounds
      // change call with the appropriate visible rect when the view is getting attached or
      // detached.
      final Rect actualVisibleRect = new Rect();
      areBoundsVisible = getLocalVisibleRect(actualVisibleRect);
      visibleRectToUse = actualVisibleRect;
    } else {
      // Since we don't have a reliable way to determine if the current view is visible or not, we
      // have to use an empty rect to unmount all components.
      areBoundsVisible = false;
      visibleRectToUse = new Rect(rect);
    }

    if (areBoundsVisible
        || (mountWhenAttachAndDetach && (rect != null && rect.isEmpty()))
        || hasComponentsExcludedFromIncrementalMount(getCurrentLayoutState())
        // It might not be yet visible but animating from 0 height/width in which case we still
        // need to mount them to trigger animation.
        || animatingRootBoundsFromZero(visibleRectToUse)) {
      mountComponent(visibleRectToUse, true);
    }
  }

  // This is used to detect if the rect is visible or not, more details could be found at here:
  // https://developer.android.com/reference/android/view/View#getLocalVisibleRect(android.graphics.Rect)
  private boolean isRectVisible(Rect rect) {
    int width = getMeasuredWidth();
    int height = getMeasuredHeight();

    if (width == 0 || height == 0) {
      // It means the view is not measured yet.
      return false;
    }

    if (rect.isEmpty()) {
      return false;
    }

    if ((rect.left < 0 && rect.right <= 0) || (rect.top < 0 && rect.bottom <= 0)) {
      // It means the view is outside of left or top of the screen.
      return false;
    }

    if ((rect.left >= width && rect.right > width)
        || (rect.top >= height && rect.bottom > height)) {
      // It means the view is outside of right or bottom of the screen.
      return false;
    }

    return true;
  }

  private boolean animatingRootBoundsFromZero(Rect currentVisibleArea) {
    final LayoutState layoutState = getCurrentLayoutState();

    return hasTree()
        && !hasMountedAtLeastOnce()
        && layoutState != null
        && ((layoutState.getRootHeightAnimation() != null && currentVisibleArea.height() == 0)
            || (layoutState.getRootWidthAnimation() != null && currentVisibleArea.width() == 0));
  }

  // Check if we should ignore the result of visible rect checking and continue doing
  // IncrementalMount.
  private static boolean hasComponentsExcludedFromIncrementalMount(
      @Nullable LayoutState layoutState) {
    return layoutState != null && layoutState.getHasComponentsExcludedFromIncrementalMount();
  }

  @VisibleForTesting
  void processVisibilityOutputs(Rect currentVisibleArea) {
    if (getCurrentLayoutState() == null || !isVisibilityProcessingEnabled()) {
      return;
    }

    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("BaseMountingView.processVisibilityOutputs");
    }
    try {
      final LayoutState layoutState = getCurrentLayoutState();

      if (layoutState == null) {
        Log.w(TAG, "Main Thread Layout state is not found");
        return;
      }

      layoutState.setShouldProcessVisibilityOutputs(true);

      if (mLithoHostListenerCoordinator != null) {
        mLithoHostListenerCoordinator.processVisibilityOutputs(
            currentVisibleArea, isMountStateDirty());
      }

      mPreviousMountVisibleRectBounds.set(currentVisibleArea);
    } finally {
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  boolean mountComponentIfNeeded() {
    if (isMountStateDirty() || mountStateNeedsRemount()) {
      boolean isTracing = ComponentsSystrace.isTracing();

      if (isTracing) {
        ComponentsSystrace.beginSection("BaseMountingView::mountComponentIfNeeded");
      }

      if (isIncrementalMountEnabled()) {
        notifyVisibleBoundsChanged(null);
      } else {
        final Rect visibleRect = new Rect();
        getLocalVisibleRect(visibleRect);
        mountComponent(visibleRect, true);
      }

      if (isTracing) {
        ComponentsSystrace.endSection();
      }

      return true;
    }

    return false;
  }

  boolean isMountStateDirty() {
    return mIsMountStateDirty;
  }

  protected void setupMountExtensions() {
    if (mLithoHostListenerCoordinator == null) {
      mLithoHostListenerCoordinator = new LithoHostListenerCoordinator(mMountState);

      mLithoHostListenerCoordinator.enableNestedLithoViewsExtension();
      mLithoHostListenerCoordinator.enableTransitions();

      if (ComponentsConfiguration.isEndToEndTestRun) {
        mLithoHostListenerCoordinator.enableEndToEndTestProcessing();
      }

      mLithoHostListenerCoordinator.enableDynamicProps();
    }

    if (hasTree()) {
      if (isIncrementalMountEnabled()) {
        final @Nullable ComponentsConfiguration config = getConfiguration();
        boolean useGapWorker = config != null && config.useIncrementalMountGapWorker;
        mLithoHostListenerCoordinator.enableIncrementalMount(useGapWorker);
      } else {
        mLithoHostListenerCoordinator.disableIncrementalMount();
      }

      if (isVisibilityProcessingEnabled()) {
        mLithoHostListenerCoordinator.enableVisibilityProcessing(this);
      } else {
        mLithoHostListenerCoordinator.disableVisibilityProcessing();
      }
    }

    mLithoHostListenerCoordinator.setCollectNotifyVisibleBoundsChangedCalls(true);
  }

  /**
   * If we have transition key on root component we might run bounds animation on BaseMountingView
   * which requires to know animating value in {@link BaseMountingView#onMeasure(int, int)}. In such
   * case we need to collect all transitions before mount happens but after layout computation is
   * finalized.
   */
  void maybeCollectAllTransitions() {
    if (mIsMountStateDirty) {
      if (!hasTree()) {
        return;
      }

      final LayoutState layoutState = getCurrentLayoutState();
      if (layoutState == null || layoutState.getRootTransitionId() == null) {
        return;
      }
      // TODO: can this be a generic callback?
      if (mLithoHostListenerCoordinator != null) {
        mLithoHostListenerCoordinator.collectAllTransitions(layoutState);
      }
    }
  }

  boolean isMounting() {
    return mIsMounting;
  }

  void resetVisibilityHint() {
    mHasVisibilityHint = false;
    mPauseMountingWhileVisibilityHintFalse = false;
  }

  void setVisibilityHintNonRecursive(boolean isVisible) {
    assertMainThread();

    if (!hasTree()) {
      return;
    }

    if (!mHasVisibilityHint && isVisible) {
      return;
    }

    // If the BaseMountingView previously had the visibility hint set to false, then when it's set
    // back
    // to true we should trigger a mount, in case the visible bounds changed while mounting was
    // paused.
    mHasVisibilityHint = true;
    mPauseMountingWhileVisibilityHintFalse = true;

    final boolean forceMount = shouldPauseMountingWithVisibilityHintFalse();
    mVisibilityHintIsVisible = isVisible;

    if (isVisible) {
      if (shouldDispatchVisibilityEvent()) {
        if (forceMount) {
          notifyVisibleBoundsChanged();
        } else if (getLocalVisibleRect(mRect)) {
          processVisibilityOutputs(mRect);
        }
      }
    } else {
      // if false: no-op, doesn't have visible area, is not ready or not attached
      clearVisibilityItems();
    }
  }

  /**
   * Deprecated: Consider subscribing the LithoView to a LithoLifecycleOwner instead.
   *
   * <p>Call this to tell the LithoView whether it is visible or not. In general, you shouldn't
   * require this as the system will do this for you. However, when a new activity/fragment is added
   * on top of the one hosting this view, the LithoView remains in the backstack but receives no
   * callback to indicate that it is no longer visible.
   *
   * <p>While the LithoView has the visibility hint set to false, it will be treated by the
   * framework as not in the viewport, so no new mounting events will be processed until the
   * visibility hint is set back to true.
   *
   * @param isVisible if true, this will find the current visible rect and process visibility
   *     outputs using it. If false, any invisible and unfocused events will be called.
   */
  @Deprecated
  public void setVisibilityHint(boolean isVisible) {
    setVisibilityHintInternal(isVisible, true);
  }

  /**
   * Marked as @Deprecated. {@link #setVisibilityHint(boolean)} should be used instead, which by
   * default does not process new mount events while the visibility hint is set to false
   * (skipMountingIfNotVisible should be set to true). This method should only be used to maintain
   * the contract with the usages of setVisibilityHint before `skipMountingIfNotVisible` was made to
   * default to true. All usages should be audited and migrated to {@link
   * #setVisibilityHint(boolean)}.
   */
  @Deprecated
  public void setVisibilityHint(boolean isVisible, boolean skipMountingIfNotVisible) {
    if (mSkipMountingIfNotVisible) {
      setVisibilityHint(isVisible);
      return;
    }

    setVisibilityHintInternal(isVisible, skipMountingIfNotVisible);
  }

  private void setVisibilityHintInternal(boolean isVisible, boolean skipMountingIfNotVisible) {
    assertMainThread();
    if (!hasTree()) {
      return;
    }

    // If the BaseMountingView previously had the visibility hint set to false, then when it's set
    // back
    // to true we should trigger a mount, in case the visible bounds changed while mounting was
    // paused.
    mHasVisibilityHint = true;
    mPauseMountingWhileVisibilityHintFalse = skipMountingIfNotVisible;

    final boolean forceMount = shouldPauseMountingWithVisibilityHintFalse();
    mVisibilityHintIsVisible = isVisible;

    if (isVisible) {
      if (shouldDispatchVisibilityEvent()) {
        if (forceMount) {
          notifyVisibleBoundsChanged();
        } else if (getLocalVisibleRect(mRect)) {
          processVisibilityOutputs(mRect);
        }
        recursivelySetVisibleHint(true, skipMountingIfNotVisible);
      }
    } else {
      // if false: no-op, doesn't have visible area, is not ready or not attached
      recursivelySetVisibleHint(false, skipMountingIfNotVisible);
      clearVisibilityItems();
    }
  }

  private void clearVisibilityItems() {
    if (mLithoHostListenerCoordinator != null) {
      mLithoHostListenerCoordinator.clearVisibilityItems();
    }
  }

  /** This should be called when setting a null component tree to the litho view. */
  private void clearLastMountedTree() {
    if (mLithoHostListenerCoordinator != null) {
      mLithoHostListenerCoordinator.clearLastMountedTreeId();
    }
  }

  /**
   * Since detached LithoView is still receiving visibility event and ends up that IM being kicked
   * off, we need to make sure that current view is in the state of being attached. Another fact is
   * that we could get an incorrect visible bounds when view gets detached.
   *
   * @return True if LithoView is attached and we need to invoke IM or dispatch visibility event.
   */
  private boolean shouldDispatchVisibilityEvent() {
    final @Nullable ComponentsConfiguration config = getConfiguration();
    return !(config != null && config.enableFixForIM && !isAttached());
  }

  /**
   * @return the width value that that the MountingView should be animating from. If this returns
   *     non-negative value, we will override the measured width with this value so that initial
   *     animated value is correctly applied.
   */
  @ThreadConfined(ThreadConfined.UI)
  int getInitialAnimatedMountingViewWidth(int currentAnimatedWidth, boolean hasNewComponentTree) {
    final Transition.RootBoundsTransition transition =
        getCurrentLayoutState() != null ? getCurrentLayoutState().getRootWidthAnimation() : null;
    return getInitialAnimatedMountingViewDimension(
        currentAnimatedWidth, hasNewComponentTree, transition, AnimatedProperties.WIDTH);
  }

  /**
   * @return the height value that the MountingView should be animating from. If this returns
   *     non-negative value, we will override the measured height with this value so that initial
   *     animated value is correctly applied.
   */
  @ThreadConfined(ThreadConfined.UI)
  int getInitialAnimatedMountingViewHeight(int currentAnimatedHeight, boolean hasNewComponentTree) {
    final Transition.RootBoundsTransition transition =
        getCurrentLayoutState() != null ? getCurrentLayoutState().getRootHeightAnimation() : null;
    return getInitialAnimatedMountingViewDimension(
        currentAnimatedHeight, hasNewComponentTree, transition, AnimatedProperties.HEIGHT);
  }

  private int getInitialAnimatedMountingViewDimension(
      int currentAnimatedDimension,
      boolean hasNewComponentTree,
      @Nullable Transition.RootBoundsTransition rootBoundsTransition,
      AnimatedProperty property) {
    if (rootBoundsTransition == null) {
      return SIZE_UNSET;
    }
    final boolean hasMounted = hasMountedAtLeastOnce();
    if (!hasMounted && rootBoundsTransition.appearTransition != null) {
      return (int)
          Transition.getRootAppearFromValue(
              rootBoundsTransition.appearTransition,
              Preconditions.checkNotNull(getCurrentLayoutState()),
              property);
    }

    if (hasMounted && !hasNewComponentTree) {
      return currentAnimatedDimension;
    }

    return SIZE_UNSET;
  }

  private void recursivelySetVisibleHint(boolean isVisible, boolean skipMountingIfNotVisible) {
    final List<BaseMountingView> childMountingViews =
        getChildMountingViewsFromCurrentlyMountedItems();
    for (int i = childMountingViews.size() - 1; i >= 0; i--) {
      final BaseMountingView mountingView = childMountingViews.get(i);
      mountingView.setVisibilityHint(isVisible, skipMountingIfNotVisible);
    }
  }

  @Override
  public void offsetTopAndBottom(int offset) {
    super.offsetTopAndBottom(offset);

    onOffsetOrTranslationChange();
  }

  @Override
  public void offsetLeftAndRight(int offset) {
    super.offsetLeftAndRight(offset);

    onOffsetOrTranslationChange();
  }

  @Override
  public void setTranslationX(float translationX) {
    if (translationX == getTranslationX()) {
      return;
    }
    super.setTranslationX(translationX);

    onOffsetOrTranslationChange();
  }

  @Override
  public void setTranslationY(float translationY) {
    if (translationY == getTranslationY()) {
      return;
    }
    super.setTranslationY(translationY);

    onOffsetOrTranslationChange();
  }

  private void onOffsetOrTranslationChange() {
    if (!hasTree() || !(getParent() instanceof View)) {
      return;
    }

    int parentWidth = ((View) getParent()).getWidth();
    int parentHeight = ((View) getParent()).getHeight();

    final int translationX = (int) getTranslationX();
    final int translationY = (int) getTranslationY();
    final int top = getTop() + translationY;
    final int bottom = getBottom() + translationY;
    final int left = getLeft() + translationX;
    final int right = getRight() + translationX;
    final Rect previousRect = mPreviousMountVisibleRectBounds;

    // Since we could have customized visible bounds, which means we should still run visibility
    // extension to see if there's any visibility event to dispatch and cannot simply early return
    // due to fully visible.
    ComponentsConfiguration configuration = getConfiguration();
    boolean hasVisibilityBoundsTransformer =
        configuration != null && configuration.visibilityBoundsTransformer != null;

    if (left >= 0
        && top >= 0
        && right <= parentWidth
        && bottom <= parentHeight
        && previousRect.left >= 0
        && previousRect.top >= 0
        && previousRect.right <= parentWidth
        && previousRect.bottom <= parentHeight
        && previousRect.width() == getWidth()
        && previousRect.height() == getHeight()
        && !hasVisibilityBoundsTransformer) {
      // View is fully visible, and has already been completely mounted.
      return;
    }

    final Rect rect = new Rect();
    if (!getLocalVisibleRect(rect)) {
      // View is not visible at all, nothing to do.
      return;
    }

    notifyVisibleBoundsChanged(rect, true);
  }

  protected @Nullable LithoHostListenerCoordinator getLithoHostListenerCoordinator() {
    return mLithoHostListenerCoordinator;
  }

  @Nullable
  VisibilityMountExtension.VisibilityMountExtensionState getVisibilityExtensionState() {

    LithoHostListenerCoordinator lithoHostListenerCoordinator = getLithoHostListenerCoordinator();
    if (lithoHostListenerCoordinator != null) {
      ExtensionState visibilityExtensionState =
          lithoHostListenerCoordinator.getVisibilityExtensionState();
      if (visibilityExtensionState != null) {
        return (VisibilityMountExtension.VisibilityMountExtensionState)
            visibilityExtensionState.getState();
      }
    }

    return null;
  }

  @Override
  protected boolean shouldRequestLayout() {
    // Don't bubble up layout requests while mounting.
    if (hasTree() && mIsMounting) {
      return false;
    }

    return super.shouldRequestLayout();
  }

  public abstract @Nullable ComponentsConfiguration getConfiguration();

  protected abstract boolean isVisibilityProcessingEnabled();

  public abstract boolean isIncrementalMountEnabled();

  @Nullable
  public abstract LayoutState getCurrentLayoutState();

  @Nullable
  protected abstract TreeState getTreeState();

  protected abstract boolean hasTree();

  protected String getTreeName() {
    final LayoutState layoutState = getCurrentLayoutState();

    return layoutState != null ? layoutState.getRootName() : "";
  }

  static void drawDebugOverlay(@Nullable BaseMountingView view, int id) {
    if (DebugOverlay.isEnabled && view != null) {
      Drawable drawable = DebugOverlay.getDebugOverlay(id);
      clearDebugOverlay(view);
      drawable.setBounds(0, 0, view.getWidth(), view.getHeight());
      view.getOverlay().add(drawable);
    }
  }

  static void clearDebugOverlay(@Nullable BaseMountingView view) {
    if (DebugOverlay.isEnabled && view != null) {
      view.getOverlay().clear();
    }
  }

  /**
   * An encapsulation of currentVisibleArea and processVisibilityOutputs for each re-entrant mount.
   */
  private static final class ReentrantMount {

    final @Nullable Rect currentVisibleArea;
    final boolean processVisibilityOutputs;

    private ReentrantMount(@Nullable Rect currentVisibleArea, boolean processVisibilityOutputs) {
      this.currentVisibleArea = currentVisibleArea;
      this.processVisibilityOutputs = processVisibilityOutputs;
    }
  }

  public interface OnDirtyMountListener {

    /**
     * Called when finishing a mount where the mount state was dirty. This indicates that there were
     * new props/state in the tree, or the BaseMountingView was mounting a new ComponentTree
     */
    void onDirtyMount(BaseMountingView view);
  }
}
