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

import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.collection.SparseArrayCompat;
import com.facebook.litho.Transition.TransitionUnit;
import com.facebook.litho.animation.AnimatedProperties;
import com.facebook.litho.animation.AnimatedProperty;
import com.facebook.litho.animation.AnimatedPropertyNode;
import com.facebook.litho.animation.AnimationBinding;
import com.facebook.litho.animation.AnimationBindingListener;
import com.facebook.litho.animation.ParallelBinding;
import com.facebook.litho.animation.PropertyAnimation;
import com.facebook.litho.animation.PropertyHandle;
import com.facebook.litho.animation.Resolver;
import com.facebook.rendercore.Host;
import com.facebook.rendercore.Systracer;
import com.facebook.rendercore.transitions.TransitionsExtensionInput;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles animating transitions defined by ComponentSpec's onCreateTransition code.
 *
 * <p>USAGE FROM MOUNTSTATE
 *
 * <p>Unique per MountState instance. Called from MountState on mount calls to process the
 * transition keys and handles which transitions to run and when.
 *
 * <p>This class is tightly coupled to MountState. When creating new animations, the expected usage
 * of this class is: 1. {@link #setupTransitions} is called with the current and next {@link
 * TransitionsExtensionInput}. 2. {@link #isAnimating} and {@link #isDisappearing} can be called to
 * determine what is/will be animating 3. MountState updates the mount content for changing content.
 * 4. {@link #runTransitions} is called to restore initial states for the transition and run the new
 * animations.
 *
 * <p>Additionally, any time the {@link MountState} is re-used for a different component tree (e.g.
 * because it was recycled in a RecyclerView), {@link #reset} should be called to stop running all
 * existing animations.
 *
 * <p>TECHNICAL DETAILS
 *
 * <p>- Transition keys are 1-1 mapped to AnimationState - An {@link AnimationState} has many {@link
 * PropertyState}s (one for each property) - A {@link PropertyState} can have up to one animation.
 *
 * <p>An {@link AnimationState} keeps track of the current mount content object, as well as the
 * state of all animating properties ({@link PropertyState}s). A {@link PropertyState} keeps track
 * of a {@link AnimatedPropertyNode}, which has the current value of that property in the animation,
 * and up to one animation and end value. A reverse mapping from animation to property(s) being
 * animated is tracked in {@link #mAnimationsToPropertyHandles}.
 *
 * <p>Combined, these mean that at any point in time, we're able to tell what animation is animating
 * what property(s). Knowing this, we can properly resolve conflicting animations (animations on the
 * same property of the same mount content).
 *
 * <p>Another important note: sometimes we need to keep values set on properties before or after
 * animations.
 *
 * <p>Examples include an appearFrom value for an animation that starts later in a sequence of
 * animations (in that case, the appearFrom value must be immediately applied even if the animation
 * isn't starting until later), and keeping disappearTo values even after an animation has completed
 * (e.g., consider animating alpha and X position: if the alpha animation finishes first, we still
 * need to keep the final value until we can remove the animating content).
 *
 * <p>As such, our rule is that we should have a {@link PropertyState} on the corresponding {@link
 * AnimationState} for any property that has a value no necessarily reflected by the most up to date
 * {@link LayoutOutput} for that transition key in the most recent {@link
 * TransitionsExtensionInput}. Put another way, animation doesn't always imply movement, but a
 * temporary change from a canonical {@link LayoutOutput}.
 */
public class TransitionManager {

  @Nullable private final AnimationInconsistencyDebugger mAnimationInconsistencyDebugger;

  /**
   * Whether we should remove the {@link PropertyState} in the {@link
   * TransitionManager#mInitialStatesToRestore} for transitions that are cleaned up by {@link
   * TransitionManager#finishUndeclaredTransitions()} or {@link
   * TransitionManager#cleanupNonAnimatingAnimationStates()}.
   *
   * <p>There is a suspicion that this is leading to crashes where we try to restore an animation
   * state, but there is no longer an animation state for it.
   */
  private static boolean sRemoveStateToRestoreIfAnimationIsCleanedUp = false;

  public static void enableInitialStateCleanupAfterAnimationCleanup(boolean enabled) {
    sRemoveStateToRestoreIfAnimationIsCleanedUp = enabled;
  }

  private static boolean sClearInitialStatesOnReset = false;

  public static void setClearInitialStatesOnReset(boolean enabled) {
    sClearInitialStatesOnReset = enabled;
  }

  /**
   * Whether a piece of content identified by a transition key is appearing, disappearing, or just
   * possibly changing some properties.
   */
  @IntDef({ChangeType.APPEARED, ChangeType.CHANGED, ChangeType.DISAPPEARED, ChangeType.UNSET})
  @Retention(RetentionPolicy.SOURCE)
  @interface ChangeType {
    int UNSET = -1;
    int APPEARED = 0;
    int CHANGED = 1;
    int DISAPPEARED = 2;
  }

  /** A listener that will be invoked when a mount content has stopped animating. */
  public interface OnAnimationCompleteListener<T> {
    void onAnimationComplete(TransitionId transitionId);

    void onAnimationUnitComplete(PropertyHandle propertyHandle, T data);
  }

  /** The animation state of a single property (e.g. X, Y, ALPHA) on a piece of mount content. */
  protected static class PropertyState {

    /**
     * The {@link AnimatedPropertyNode} for this property: it contains the current animated value
     * and a way to set a new value.
     */
    public AnimatedPropertyNode animatedPropertyNode;

    /** The animation, if any, that is currently running on this property. */
    public AnimationBinding animation;

    /** If there's an {@link #animation}, the target value it's animating to. */
    public Float targetValue;

    /** The last mounted value of this property. */
    public Float lastMountedValue;

    /** How many animations are waiting to finish for this property. */
    public int numPendingAnimations;
  }

  /**
   * Animation state of a given mount content. Holds everything we currently know about an animating
   * transition key, such as whether it's appearing, disappearing, or changing, as well as
   * information about any animating properties on this mount content.
   */
  static class AnimationState {

    /**
     * The states for all the properties of this mount content that have an animated value (e.g. a
     * value that isn't necessarily their mounted value).
     */
    public final Map<AnimatedProperty, PropertyState> propertyStates = new HashMap<>();

    /**
     * The current mount content for this animation state, if it's mounted, null otherwise. This
     * mount content can change over time.
     */
    public @Nullable OutputUnitsAffinityGroup<Object> mountContentGroup;

    /**
     * Whether the last {@link TransitionsExtensionInput} diff that had this content in it showed
     * the content appearing, disappearing or changing.
     */
    public int changeType = ChangeType.UNSET;

    /** While calculating animations, the current (before) LayoutOutput. */
    public @Nullable OutputUnitsAffinityGroup<AnimatableItem> currentLayoutOutputsGroup;

    /** While calculating animations, the next (after) LayoutOutput. */
    public @Nullable OutputUnitsAffinityGroup<AnimatableItem> nextLayoutOutputsGroup;

    /**
     * Whether this transition key was seen in the last transition, either in the current or next
     * {@link TransitionsExtensionInput}.
     */
    public boolean seenInLastTransition = false;

    /**
     * If this animation is running but the layout changed and it appeared/diappeared without an
     * equivalent Transition specified, we need to interrupt this animation.
     */
    public boolean shouldFinishUndeclaredAnimation;

    public boolean hasDisappearingAnimation;
  }

  private final Map<AnimationBinding, List<PropertyHandle>> mAnimationsToPropertyHandles =
      new HashMap<>();
  private final TransitionIdMap<AnimationState> mAnimationStates = new TransitionIdMap<>();
  private final SparseArrayCompat<String> mTraceNames = new SparseArrayCompat<>();
  private final Map<PropertyHandle, Float> mInitialStatesToRestore = new HashMap<>();
  private final ArrayList<AnimationBinding> mRunningRootAnimations = new ArrayList<>();
  private final TransitionsAnimationBindingListener mAnimationBindingListener =
      new TransitionsAnimationBindingListener();
  private final RootAnimationListener mRootAnimationListener = new RootAnimationListener();
  private final TransitionsResolver mResolver = new TransitionsResolver();
  @Nullable private final OnAnimationCompleteListener mOnAnimationCompleteListener;
  @Nullable private AnimationBinding mRootAnimationToRun;
  @Nullable private final String mDebugTag;
  private final Map<Host, Boolean> mOverriddenClipChildrenFlags = new LinkedHashMap<>();
  private final Systracer mTracer;

  public TransitionManager(
      OnAnimationCompleteListener onAnimationCompleteListener,
      @Nullable final String debugTag,
      Systracer systracer) {
    mOnAnimationCompleteListener = onAnimationCompleteListener;
    mDebugTag = debugTag;
    mTracer = systracer;
    mAnimationInconsistencyDebugger = AnimationInconsistencyDebugger.Factory.get();
  }

  void setupTransitions(
      TransitionsExtensionInput currentInput,
      TransitionsExtensionInput nextInput,
      Transition rootTransition) {
    final Map<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>> nextTransitionIds =
        nextInput.getTransitionIdMapping();
    final Map<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>> currentTransitionIds =
        currentInput == null ? null : currentInput.getTransitionIdMapping();
    setupTransitions(currentTransitionIds, nextTransitionIds, rootTransition);
  }

  /**
   * Creates (but doesn't start) the animations for the next transition based on the current and
   * next layout states.
   *
   * <p>After this is called, MountState can use {@link #isAnimating} and {@link #isDisappearing} to
   * check whether certain mount content will animate, commit the layout changes, and then call
   * {@link #runTransitions} to restore the initial states and run the animations.
   */
  void setupTransitions(
      @Nullable Map<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>> currentTransitionIds,
      @Nullable Map<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>> nextTransitionIds,
      Transition rootTransition) {
    if (mDebugTag != null) {
      Log.d(mDebugTag, "=== SetupTransitions ===");
    }

    mTracer.beginSection("TransitionManager.setupTransition");

    for (AnimationState animationState : mAnimationStates.values()) {
      animationState.seenInLastTransition = false;
    }

    if (currentTransitionIds == null) {
      for (Map.Entry<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>> nextTransitionId :
          nextTransitionIds.entrySet()) {
        TransitionId transitionId = nextTransitionId.getKey();

        final OutputUnitsAffinityGroup<AnimatableItem> nextLayoutOutputsGroup =
            nextTransitionId.getValue();
        recordLayoutOutputsGroupDiff(transitionId, null, nextLayoutOutputsGroup);
      }
    } else {
      final HashSet<TransitionId> seenInNewLayout = new HashSet<>();

      for (TransitionId transitionId : nextTransitionIds.keySet()) {
        final boolean isAutogenerated = transitionId.mType == TransitionId.Type.AUTOGENERATED;

        final OutputUnitsAffinityGroup<AnimatableItem> nextLayoutOutputsGroup =
            nextTransitionIds.get(transitionId);
        final OutputUnitsAffinityGroup<AnimatableItem> currentLayoutOutputsGroup =
            currentTransitionIds.get(transitionId);

        if (nextLayoutOutputsGroup != null) {
          seenInNewLayout.add(transitionId);
        } else if (isAutogenerated) {
          // Only appearing animation would be possible, but there is no way to declare appearing
          // animation for autogenerated ids
          continue;
        }

        recordLayoutOutputsGroupDiff(
            transitionId, currentLayoutOutputsGroup, nextLayoutOutputsGroup);
      }

      for (TransitionId transitionId : currentTransitionIds.keySet()) {
        if (seenInNewLayout.contains(transitionId)) {
          // We either already processed this id or it's autogenerated and is not present in the
          // new layout, thus only disappearing animation would be possible, but there is no way to
          // declare disappearing animation for autogenerated ids
          continue;
        }
        recordLayoutOutputsGroupDiff(transitionId, currentTransitionIds.get(transitionId), null);
      }
    }

    createTransitionAnimations(rootTransition);

    // If we recorded any mount content diffs that didn't result in an animation being created for
    // that transition id, clean them up now.
    cleanupNonAnimatingAnimationStates();

    mTracer.endSection();
  }

  /**
   * This method will check for running transitions which do not exist after a layout change.
   * Therefore, they need to be interrupted and "finished".
   */
  // TODO: This is only catching changes in appeared/disappeared items. We need to investigate items
  //       which change without a change transition declared. Also the flag should probably belong
  //       to the properties and not to the AnimationState.
  void finishUndeclaredTransitions() {
    if (sRemoveStateToRestoreIfAnimationIsCleanedUp) {
      Set<TransitionId> toRemove = new HashSet<>();
      for (TransitionId transitionId : new ArrayList<>(mAnimationStates.ids())) {
        AnimationState animationState = mAnimationStates.get(transitionId);
        if (animationState.shouldFinishUndeclaredAnimation) {
          animationState.shouldFinishUndeclaredAnimation = false;

          for (PropertyState propertyState :
              new ArrayList<>(animationState.propertyStates.values())) {
            final AnimationBinding animationBinding = propertyState.animation;
            if (animationBinding != null) {
              animationBinding.stop();
              mAnimationBindingListener.finishAnimation(
                  animationBinding, AnimationCleanupTrigger.UNDECLARED_TRANSITIONS);
            }
          }

          toRemove.add(transitionId);
        }
      }

      removeStatesToRestoreForTransitionIds(toRemove);
    } else {
      for (AnimationState animationState : new ArrayList<>(mAnimationStates.values())) {
        if (animationState.shouldFinishUndeclaredAnimation) {
          animationState.shouldFinishUndeclaredAnimation = false;

          for (PropertyState propertyState :
              new ArrayList<>(animationState.propertyStates.values())) {
            final AnimationBinding animationBinding = propertyState.animation;
            if (animationBinding != null) {
              animationBinding.stop();
              mAnimationBindingListener.finishAnimation(
                  animationBinding, AnimationCleanupTrigger.UNDECLARED_TRANSITIONS);
            }
          }
        }
      }
    }
  }

  private void removeStatesToRestoreForTransitionIds(Set<TransitionId> toRemove) {
    if (!toRemove.isEmpty()) {
      for (PropertyHandle propertyHandle : new ArrayList<>(mInitialStatesToRestore.keySet())) {
        TransitionId transitionId =
            propertyHandle != null ? propertyHandle.getTransitionId() : null;
        if (transitionId != null && toRemove.contains(transitionId)) {
          mInitialStatesToRestore.remove(propertyHandle);
        }
      }
    }
  }

  /**
   * Called after {@link #setupTransitions} has been called and the new layout has been mounted.
   * This restores the state of the previous layout for content that will animate and then starts
   * the corresponding animations.
   */
  void runTransitions() {
    mTracer.beginSection("runTransitions");

    restoreInitialStates();

    if (mDebugTag != null) {
      debugLogStartingAnimations();
    }

    if (mRootAnimationToRun != null) {
      mRootAnimationToRun.addListener(mRootAnimationListener);
      mRootAnimationToRun.start(mResolver);
      mRootAnimationToRun = null;
    }

    mTracer.endSection();
  }

  /**
   * Sets the mount content for a given key. This is used to initially set mount content, but also
   * to set content when content is incrementally mounted during an animation.
   */
  void setMountContent(
      TransitionId transitionId, @Nullable OutputUnitsAffinityGroup<Object> mountContentGroup) {
    final AnimationState animationState = mAnimationStates.get(transitionId);
    if (animationState != null) {
      setMountContentInner(transitionId, animationState, mountContentGroup);
    }
  }

  /**
   * After transitions have been setup with {@link #setupTransitions}, returns whether the given key
   * will be/is animating.
   */
  boolean isAnimating(TransitionId transitionId) {
    return mAnimationStates.contains(transitionId);
  }

  /**
   * After transitions have been setup with {@link #setupTransitions}, returns whether the given key
   * is disappearing.
   */
  boolean isDisappearing(TransitionId transitionId) {
    final AnimationState animationState = mAnimationStates.get(transitionId);
    if (animationState == null) {
      return false;
    }
    return animationState.changeType == ChangeType.DISAPPEARED
        && animationState.hasDisappearingAnimation;
  }

  /** To be called when a MountState is recycled for a new component tree. Clears all animations. */
  void reset() {
    if (mAnimationInconsistencyDebugger != null) {
      mAnimationInconsistencyDebugger.reset(mAnimationStates);
    }

    if (sClearInitialStatesOnReset) {
      mInitialStatesToRestore.clear();
    }

    for (TransitionId transitionId : mAnimationStates.ids()) {
      final AnimationState animationState = mAnimationStates.get(transitionId);
      setMountContentInner(transitionId, animationState, null);
      clearLayoutOutputs(animationState);
    }
    mAnimationStates.clear();

    mTraceNames.clear();

    // Clear these so that stopping animations below doesn't cause us to trigger any useless
    // cleanup.
    mAnimationsToPropertyHandles.clear();

    // Calling stop will cause the animation to be removed from the set, so iterate in reverse
    // order.
    for (int i = mRunningRootAnimations.size() - 1; i >= 0; i--) {
      mRunningRootAnimations.get(i).stop();
    }
    mRunningRootAnimations.clear();

    mRootAnimationToRun = null;
    mOverriddenClipChildrenFlags.clear();
  }

  /**
   * Called to record the current/next content for a transition key.
   *
   * @param currentLayoutOutputsGroup the current group of LayoutOutputs for this key, or null if
   *     the key is appearing
   * @param nextLayoutOutputsGroup the new group of LayoutOutput for this key, or null if the key is
   *     disappearing
   */
  private void recordLayoutOutputsGroupDiff(
      TransitionId transitionId,
      @Nullable OutputUnitsAffinityGroup<AnimatableItem> currentLayoutOutputsGroup,
      OutputUnitsAffinityGroup<AnimatableItem> nextLayoutOutputsGroup) {
    AnimationState animationState = mAnimationStates.get(transitionId);
    if (animationState == null) {
      animationState = new AnimationState();
      mAnimationStates.put(transitionId, animationState);
      if (mAnimationInconsistencyDebugger != null) {
        mAnimationInconsistencyDebugger.trackAnimationStateCreated(transitionId);
      }
    }

    if (currentLayoutOutputsGroup == null && nextLayoutOutputsGroup == null) {
      throw new RuntimeException("Both current and next LayoutOutput groups were null!");
    }

    if (currentLayoutOutputsGroup == null && nextLayoutOutputsGroup != null) {
      animationState.changeType = ChangeType.APPEARED;
    } else if (currentLayoutOutputsGroup != null && nextLayoutOutputsGroup != null) {
      animationState.changeType = ChangeType.CHANGED;
    } else {
      if ((animationState.changeType == ChangeType.APPEARED
              || animationState.changeType == ChangeType.CHANGED)
          && !animationState.hasDisappearingAnimation) {
        animationState.shouldFinishUndeclaredAnimation = true;
      }
      animationState.changeType = ChangeType.DISAPPEARED;
    }

    animationState.currentLayoutOutputsGroup = currentLayoutOutputsGroup;
    animationState.nextLayoutOutputsGroup = nextLayoutOutputsGroup;

    recordLastMountedValues(animationState);

    animationState.seenInLastTransition = true;

    if (mDebugTag != null) {
      Log.d(
          mDebugTag,
          "Saw transition id "
              + transitionId
              + " which is "
              + changeTypeToString(animationState.changeType));
    }
  }

  private void recordLastMountedValues(AnimationState animationState) {
    final AnimatableItem animatableItem =
        animationState.nextLayoutOutputsGroup != null
            ? animationState.nextLayoutOutputsGroup.getMostSignificantUnit()
            : null;
    // The values for all members of the group should be the same, thus we'll be collected from the
    // most significant one
    for (AnimatedProperty property : animationState.propertyStates.keySet()) {
      final PropertyState propertyState = animationState.propertyStates.get(property);
      if (animatableItem == null) {
        propertyState.lastMountedValue = null;
      } else {
        propertyState.lastMountedValue = property.get(animatableItem);
      }
    }
  }

  @Nullable
  static Transition getRootTransition(List<Transition> allTransitions) {
    if (allTransitions.isEmpty()) {
      return null;
    }

    if (allTransitions.size() == 1) {
      return allTransitions.get(0);
    }

    return new ParallelTransitionSet(allTransitions);
  }

  private void createTransitionAnimations(Transition rootTransition) {
    mRootAnimationToRun = createAnimationsForTransition(rootTransition);
  }

  private @Nullable AnimationBinding createAnimationsForTransition(Transition transition) {
    if (transition instanceof TransitionUnit) {
      return createAnimationsForTransitionUnit((TransitionUnit) transition);
    } else if (transition instanceof TransitionSet) {
      return createAnimationsForTransitionSet((TransitionSet) transition);
    } else {
      throw new RuntimeException("Unhandled Transition type: " + transition);
    }
  }

  private @Nullable AnimationBinding createAnimationsForTransitionSet(TransitionSet transitionSet) {
    final ArrayList<Transition> children = transitionSet.getChildren();
    final ArrayList<AnimationBinding> createdAnimations = new ArrayList<>();
    for (int i = 0, size = children.size(); i < size; i++) {
      final AnimationBinding animation = createAnimationsForTransition(children.get(i));
      if (animation != null) {
        createdAnimations.add(animation);
      }
    }

    if (createdAnimations.isEmpty()) {
      return null;
    }

    return transitionSet.createAnimation(createdAnimations);
  }

  private @Nullable AnimationBinding createAnimationsForTransitionUnit(TransitionUnit transition) {
    final Transition.AnimationTarget animationTarget = transition.getAnimationTarget();
    final ArrayList<AnimationBinding> createdAnimations = new ArrayList<>();
    switch (animationTarget.componentTarget.componentTargetType) {
      case ALL:
      case AUTO_LAYOUT:
        createAnimationsForTransitionUnitAllKeys(transition, createdAnimations);
        break;

      case LOCAL_KEY:
        String key = (String) animationTarget.componentTarget.componentTargetExtraData;
        TransitionId transitionId = mAnimationStates.getScopedId(transition.getOwnerKey(), key);
        createAnimationsForTransitionUnit(transition, transitionId, createdAnimations);
        break;

      case LOCAL_KEY_SET:
        String[] keys = (String[]) animationTarget.componentTarget.componentTargetExtraData;
        final String ownerKey = transition.getOwnerKey();
        for (int j = 0; j < keys.length; j++) {
          transitionId = mAnimationStates.getScopedId(ownerKey, keys[j]);
          if (transitionId != null) {
            createAnimationsForTransitionUnit(transition, transitionId, createdAnimations);
          }
        }
        break;

      case GLOBAL_KEY:
        key = (String) animationTarget.componentTarget.componentTargetExtraData;
        transitionId = mAnimationStates.getGlobalId(key);
        createAnimationsForTransitionUnit(transition, transitionId, createdAnimations);
        break;

      case GLOBAL_KEY_SET:
        keys = (String[]) animationTarget.componentTarget.componentTargetExtraData;
        for (int j = 0; j < keys.length; j++) {
          transitionId = mAnimationStates.getGlobalId(keys[j]);
          if (transitionId != null) {
            createAnimationsForTransitionUnit(transition, transitionId, createdAnimations);
          }
        }
        break;
    }

    if (createdAnimations.isEmpty()) {
      return null;
    }

    if (createdAnimations.size() == 1) {
      return createdAnimations.get(0);
    }

    return new ParallelBinding(0, createdAnimations);
  }

  private void createAnimationsForTransitionUnitAllKeys(
      TransitionUnit transition, ArrayList<AnimationBinding> outList) {
    for (TransitionId transitionId : mAnimationStates.ids()) {
      final AnimationState animationState = mAnimationStates.get(transitionId);
      if (!animationState.seenInLastTransition) {
        continue;
      }
      createAnimationsForTransitionUnit(transition, transitionId, outList);
    }
  }

  private void createAnimationsForTransitionUnit(
      TransitionUnit transition,
      @Nullable TransitionId transitionId,
      ArrayList<AnimationBinding> outList) {
    final Transition.AnimationTarget animationTarget = transition.getAnimationTarget();
    switch (animationTarget.propertyTarget.propertyTargetType) {
      case AUTO_LAYOUT:
        for (int i = 0; i < AnimatedProperties.AUTO_LAYOUT_PROPERTIES.length; i++) {
          final AnimationBinding createdAnimation =
              maybeCreateAnimation(
                  transition, transitionId, AnimatedProperties.AUTO_LAYOUT_PROPERTIES[i]);
          if (createdAnimation != null) {
            outList.add(createdAnimation);
          }
        }
        break;
      case SET:
        final AnimatedProperty[] properties =
            (AnimatedProperty[]) animationTarget.propertyTarget.propertyTargetExtraData;
        for (int i = 0; i < properties.length; i++) {
          final AnimationBinding createdAnimation =
              maybeCreateAnimation(transition, transitionId, properties[i]);
          if (createdAnimation != null) {
            outList.add(createdAnimation);
          }
        }
        break;
      case SINGLE:
        final AnimationBinding createdAnimation =
            maybeCreateAnimation(
                transition,
                transitionId,
                (AnimatedProperty) animationTarget.propertyTarget.propertyTargetExtraData);
        if (createdAnimation != null) {
          outList.add(createdAnimation);
        }
        break;
    }
  }

  private @Nullable AnimationBinding maybeCreateAnimation(
      TransitionUnit transition, @Nullable TransitionId transitionId, AnimatedProperty property) {
    final AnimationState animationState = mAnimationStates.get(transitionId);

    if (mDebugTag != null) {
      Log.d(
          mDebugTag,
          "Calculating transitions for " + transitionId + "#" + property.getName() + ":");
    }

    if (animationState == null
        || (animationState.currentLayoutOutputsGroup == null
            && animationState.nextLayoutOutputsGroup == null)) {
      if (mDebugTag != null) {
        Log.d(mDebugTag, " - this transitionId was not seen in the before/after layout state");
      }
      return null;
    }

    animationState.hasDisappearingAnimation =
        transition.hasDisappearAnimation() || animationState.hasDisappearingAnimation;
    final int changeType = animationState.changeType;
    final String changeTypeString = changeTypeToString(animationState.changeType);
    if ((changeType == ChangeType.APPEARED && !transition.hasAppearAnimation())
        || (changeType == ChangeType.DISAPPEARED && !transition.hasDisappearAnimation())) {
      // Interrupt running transitions after a layout change, without the new changeType defined.
      animationState.shouldFinishUndeclaredAnimation = true;

      if (mDebugTag != null) {
        Log.d(mDebugTag, " - did not find matching transition for change type " + changeTypeString);
      }
      return null;
    }

    final PropertyState existingState = animationState.propertyStates.get(property);
    final PropertyHandle propertyHandle = new PropertyHandle(transitionId, property);
    final float startValue;
    if (existingState != null) {
      startValue = existingState.animatedPropertyNode.getValue();
    } else {
      if (animationState.changeType != ChangeType.APPEARED) {
        startValue =
            property.get(animationState.currentLayoutOutputsGroup.getMostSignificantUnit());
      } else {
        startValue = transition.getAppearFrom().resolve(mResolver, propertyHandle);
      }
    }

    final float endValue;
    if (animationState.changeType != ChangeType.DISAPPEARED) {
      endValue = property.get(animationState.nextLayoutOutputsGroup.getMostSignificantUnit());
    } else {
      endValue = transition.getDisappearTo().resolve(mResolver, propertyHandle);
    }

    // Don't replace new animations in two cases: 1) we're already animating that property to
    // the same end value or 2) the start and end values are already the same
    if (existingState != null && existingState.targetValue != null) {
      if (endValue == existingState.targetValue) {
        if (mDebugTag != null) {
          Log.d(mDebugTag, " - property is already animating to this end value: " + endValue);
        }
        return null;
      }
    } else if (startValue == endValue) {
      if (mDebugTag != null) {
        Log.d(
            mDebugTag,
            " - the start and end values were the same: " + startValue + " = " + endValue);
      }
      return null;
    }

    if (mDebugTag != null) {
      Log.d(mDebugTag, " - created animation (start=" + startValue + ", end=" + endValue + ")");
    }

    final AnimationBinding animation = transition.createAnimation(propertyHandle, endValue);
    animation.addListener(mAnimationBindingListener);
    // We add this transition handler to the binding
    animation.setTag(transition.getTransitionEndHandler());

    PropertyState propertyState = existingState;
    if (propertyState == null) {
      propertyState = new PropertyState();

      propertyState.animatedPropertyNode =
          new AnimatedPropertyNode(animationState.mountContentGroup, property);
      animationState.propertyStates.put(property, propertyState);
    }
    propertyState.animatedPropertyNode.setValue(startValue);
    propertyState.numPendingAnimations++;

    // Currently, all supported animations can only animate one property at a time, but we think
    // this will change in the future so we maintain a set here.
    final List<PropertyHandle> animatedPropertyHandles = new ArrayList<>();
    animatedPropertyHandles.add(propertyHandle);
    mAnimationsToPropertyHandles.put(animation, animatedPropertyHandles);
    mInitialStatesToRestore.put(propertyHandle, startValue);

    if (!TextUtils.isEmpty(transition.getTraceName())) {
      mTraceNames.put(animation.hashCode(), transition.getTraceName());
    }

    return animation;
  }

  private void restoreInitialStates() {
    PropertyHandle lastPropertyHandle = null;
    try {
      for (PropertyHandle propertyHandle : mInitialStatesToRestore.keySet()) {
        lastPropertyHandle = propertyHandle;
        final float value = mInitialStatesToRestore.get(propertyHandle);
        final TransitionId transitionId = propertyHandle.getTransitionId();
        final AnimationState animationState = mAnimationStates.get(transitionId);

        if (animationState.mountContentGroup != null) {
          setPropertyValue(propertyHandle.getProperty(), value, animationState.mountContentGroup);
        }
      }
    } catch (Exception e) {
      throw new InconsistentInitialStateRestorationException(lastPropertyHandle, e);
    }

    mInitialStatesToRestore.clear();
  }

  private class InconsistentInitialStateRestorationException extends RuntimeException {

    @Nullable private PropertyHandle lastPropHandle;

    public InconsistentInitialStateRestorationException(
        @Nullable PropertyHandle lastPropHandle, Exception cause) {
      super(cause);
      this.lastPropHandle = lastPropHandle;
    }

    @Override
    public String getMessage() {
      StringBuilder message = new StringBuilder();
      message
          .append("Inconsistent initial state restoration:\n")
          .append("- mAnimationStates (")
          .append(mAnimationStates.ids().size())
          .append("):\n")
          .append("   - ids: ")
          .append(mAnimationStates.ids())
          .append("\n- mInitialStatesToRestore (")
          .append(mInitialStatesToRestore.size())
          .append("):\n");

      for (PropertyHandle propertyHandle : mInitialStatesToRestore.keySet()) {
        Float value = mInitialStatesToRestore.get(propertyHandle);
        final TransitionId transitionid = propertyHandle.getTransitionId();
        boolean isCrashingOne =
            lastPropHandle != null && lastPropHandle.getTransitionId().equals(transitionid);
        final String propertyName = propertyHandle.getProperty().getName();

        message
            .append("   - propertyHandle[transitionId=")
            .append(transitionid)
            .append(", property=")
            .append(propertyName)
            .append("]")
            .append(isCrashingOne ? "[crashing] " : " ")
            .append(value)
            .append("\n");
      }

      if (mAnimationInconsistencyDebugger != null) {
        message.append("\n - Animation Inconsistency Debugger data: \n");
        message.append(mAnimationInconsistencyDebugger.getReadableStatus());
      }

      return message.toString();
    }
  }

  private void setMountContentInner(
      TransitionId transitionId,
      AnimationState animationState,
      @Nullable OutputUnitsAffinityGroup<Object> newMountContentGroup) {
    // If the mount content changes, this means this transition key will be rendered with a
    // different mount content (View or Drawable) than it was during the last mount, so we need to
    // migrate animation state from the old mount content to the new one.
    final OutputUnitsAffinityGroup<Object> mountContentGroup = animationState.mountContentGroup;
    if ((mountContentGroup == null && newMountContentGroup == null)
        || (mountContentGroup != null && mountContentGroup.equals(newMountContentGroup))) {
      return;
    }

    if (mDebugTag != null) {
      Log.d(mDebugTag, "Setting mount content for " + transitionId + " to " + newMountContentGroup);
    }

    final Map<AnimatedProperty, PropertyState> animatingProperties = animationState.propertyStates;
    if (animationState.mountContentGroup != null) {
      for (AnimatedProperty animatedProperty : animatingProperties.keySet()) {
        resetProperty(animatedProperty, animationState.mountContentGroup);
      }
      recursivelySetChildClippingForGroup(animationState.mountContentGroup, true);
    }

    for (PropertyState propertyState : animatingProperties.values()) {
      propertyState.animatedPropertyNode.setMountContentGroup(newMountContentGroup);
    }
    if (newMountContentGroup != null) {
      recursivelySetChildClippingForGroup(newMountContentGroup, false);
    }
    animationState.mountContentGroup = newMountContentGroup;
  }

  private void recursivelySetChildClippingForGroup(
      OutputUnitsAffinityGroup<Object> mountContentGroup, boolean clipChildren) {
    // We only need to set clipping to view containers (OutputUnitType.HOST)
    recursivelySetChildClipping(mountContentGroup.get(OutputUnitType.HOST), clipChildren);
  }

  /**
   * Set the clipChildren properties to all Views in the same tree branch from the given one, up to
   * the top LithoView.
   *
   * <p>TODO(17934271): Handle the case where two+ animations with different lifespans share the
   * same parent, in which case we shouldn't unset clipping until the last item is done animating.
   */
  private void recursivelySetChildClipping(Object mountContent, boolean clipChildren) {
    if (!(mountContent instanceof View)) {
      return;
    }

    recursivelySetChildClippingForView((View) mountContent, clipChildren);
  }

  private void recursivelySetChildClippingForView(View view, boolean clipChildren) {
    if (view instanceof Host) {
      if (clipChildren) {
        // When clip children is true we want to restore what the view had before.
        // It can happen that two different animations run on the same parent, in that case we won't
        // find the view in the map so it is ignored.
        if (mOverriddenClipChildrenFlags.containsKey(view)) {
          ((Host) view).setClipChildren(mOverriddenClipChildrenFlags.remove(view));
        }
      } else {
        // In this case we only save the actual configuration if it's not already saved, otherwise
        // we may be saving the wrong one. Then we set clip to false.
        if (!mOverriddenClipChildrenFlags.containsKey(view)) {
          mOverriddenClipChildrenFlags.put((Host) view, ((Host) view).getClipChildren());
        }
        ((Host) view).setClipChildren(false);
      }
    }

    final ViewParent parent = view.getParent();
    if (parent instanceof Host) {
      recursivelySetChildClippingForView((View) parent, clipChildren);
    }
  }

  /**
   * Removes any AnimationStates that were created in {@link #recordLayoutOutputsGroupDiff} but
   * never resulted in an animation being created.
   */
  private void cleanupNonAnimatingAnimationStates() {
    final Set<TransitionId> toRemove = new HashSet<>();

    for (TransitionId transitionId : mAnimationStates.ids()) {
      final AnimationState animationState = mAnimationStates.get(transitionId);
      if (animationState.propertyStates.isEmpty()) {
        setMountContentInner(transitionId, animationState, null);
        clearLayoutOutputs(animationState);

        toRemove.add(transitionId);
      }
    }

    for (TransitionId transitionId : toRemove) {
      removeAnimationState(transitionId, AnimationCleanupTrigger.NON_ANIMATING_CLEANUP);
    }

    if (sRemoveStateToRestoreIfAnimationIsCleanedUp) {
      removeStatesToRestoreForTransitionIds(toRemove);
    }
  }

  private void trackAnimationStateRemoval(
      TransitionId transitionId,
      AnimationCleanupTrigger animationFinishTrigger,
      @Nullable AnimationState animationState) {
    if (mAnimationInconsistencyDebugger != null) {
      mAnimationInconsistencyDebugger.trackAnimationStateRemoved(
          transitionId, animationFinishTrigger, animationState);
    }
  }

  private void debugLogStartingAnimations() {
    if (mDebugTag == null) {
      throw new RuntimeException("Trying to debug log animations without debug flag set!");
    }

    Log.d(mDebugTag, "Starting animations:");

    // TODO(t20726089): Restore introspection of animations
  }

  protected static String changeTypeToString(int changeType) {
    switch (changeType) {
      case ChangeType.APPEARED:
        return "APPEARED";
      case ChangeType.CHANGED:
        return "CHANGED";
      case ChangeType.DISAPPEARED:
        return "DISAPPEARED";
      case ChangeType.UNSET:
        return "UNSET";
      default:
        throw new RuntimeException("Unknown changeType: " + changeType);
    }
  }

  private static void clearLayoutOutputs(AnimationState animationState) {
    if (animationState.currentLayoutOutputsGroup != null) {
      animationState.currentLayoutOutputsGroup = null;
    }
    if (animationState.nextLayoutOutputsGroup != null) {
      animationState.nextLayoutOutputsGroup = null;
    }
  }

  private static float getPropertyValue(
      AnimatedProperty property, OutputUnitsAffinityGroup<AnimatableItem> mountContentGroup) {
    return property.get(mountContentGroup.getMostSignificantUnit());
  }

  private static void setPropertyValue(
      AnimatedProperty property, float value, OutputUnitsAffinityGroup<Object> mountContentGroup) {
    for (int i = 0, size = mountContentGroup.size(); i < size; i++) {
      property.set(mountContentGroup.getAt(i), value);
    }
  }

  private static void resetProperty(
      AnimatedProperty property, OutputUnitsAffinityGroup<Object> mountContentGroup) {
    for (int i = 0, size = mountContentGroup.size(); i < size; i++) {
      property.reset(mountContentGroup.getAt(i));
    }
  }

  private class TransitionsAnimationBindingListener implements AnimationBindingListener {

    private final ArrayList<PropertyAnimation> mTempPropertyAnimations = new ArrayList<>();

    @Override
    public void onScheduledToStartLater(AnimationBinding binding) {
      updateAnimationStates(binding);
    }

    @Override
    public void onWillStart(AnimationBinding binding) {
      updateAnimationStates(binding);

      final String traceName = mTraceNames.get(binding.hashCode());
      if (!TextUtils.isEmpty(traceName)) {
        mTracer.beginAsyncSection(traceName, binding.hashCode());
      }
    }

    @Override
    public void onFinish(AnimationBinding binding) {
      final List<PropertyHandle> keys = mAnimationsToPropertyHandles.get(binding);
      if (keys != null && mOnAnimationCompleteListener != null) {
        // We loop through all the properties that were animated by this animation
        for (PropertyHandle propertyHandle : keys) {
          mOnAnimationCompleteListener.onAnimationUnitComplete(propertyHandle, binding.getTag());
        }
      }
      finishAnimation(binding, AnimationCleanupTrigger.FINISHED_TO_CONCLUSION);
    }

    @Override
    public void onCanceledBeforeStart(AnimationBinding binding) {
      finishAnimation(binding, AnimationCleanupTrigger.CANCELED_BEFORE_START);
    }

    @Override
    public boolean shouldStart(AnimationBinding binding) {
      binding.collectTransitioningProperties(mTempPropertyAnimations);

      boolean shouldStart = true;

      // Make sure that all animating properties will animate to a valid position
      for (int i = 0, size = mTempPropertyAnimations.size(); i < size; i++) {
        final PropertyAnimation propertyAnimation = mTempPropertyAnimations.get(i);
        final TransitionId transitionId = propertyAnimation.getTransitionId();
        final @Nullable AnimationState animationState = mAnimationStates.get(transitionId);
        final @Nullable PropertyState propertyState =
            (animationState != null)
                ? animationState.propertyStates.get(propertyAnimation.getProperty())
                : null;

        if (mDebugTag != null) {
          Log.d(
              mDebugTag,
              "Trying to start animation on "
                  + transitionId
                  + "#"
                  + propertyAnimation.getProperty().getName()
                  + " to "
                  + propertyAnimation.getTargetValue()
                  + ":");
        }

        if (propertyState == null) {
          if (mDebugTag != null) {
            Log.d(
                mDebugTag,
                " - Canceling animation, transitionId not found in the AnimationState."
                    + " It has been probably cancelled already.");
          }

          shouldStart = false;
        }

        if (shouldStart
            && propertyState.lastMountedValue != null
            && propertyState.lastMountedValue != propertyAnimation.getTargetValue()) {
          if (mDebugTag != null) {
            Log.d(
                mDebugTag,
                " - Canceling animation, last mounted value does not equal animation target: "
                    + propertyState.lastMountedValue
                    + " != "
                    + propertyAnimation.getTargetValue());
          }

          shouldStart = false;
        }
      }

      mTempPropertyAnimations.clear();
      return shouldStart;
    }

    private void updateAnimationStates(AnimationBinding binding) {
      binding.collectTransitioningProperties(mTempPropertyAnimations);

      for (int i = 0, size = mTempPropertyAnimations.size(); i < size; i++) {
        final PropertyAnimation propertyAnimation = mTempPropertyAnimations.get(i);
        final TransitionId transitionId = propertyAnimation.getTransitionId();
        final AnimationState animationState = mAnimationStates.get(transitionId);
        if (animationState == null) {
          // This can happen when unmounting all items on running animations.
          continue;
        }
        final PropertyState propertyState =
            animationState.propertyStates.get(propertyAnimation.getProperty());

        propertyState.targetValue = propertyAnimation.getTargetValue();
        propertyState.animation = binding;
      }

      mTempPropertyAnimations.clear();
    }

    private void finishAnimation(
        AnimationBinding binding, AnimationCleanupTrigger animationFinishTrigger) {
      final List<PropertyHandle> keys = mAnimationsToPropertyHandles.remove(binding);
      if (keys == null) {
        return;
      }

      // When an animation finishes, we want to go through all the mount contents it was animating
      // and see if it was the last active animation. If it was, we know that item is no longer
      // animating and we can release the animation state.
      for (int i = 0, size = keys.size(); i < size; i++) {
        final PropertyHandle propertyHandle = keys.get(i);
        final TransitionId transitionId = propertyHandle.getTransitionId();
        final AnimationState animationState = mAnimationStates.get(transitionId);
        final AnimatedProperty property = propertyHandle.getProperty();
        final boolean isDisappearAnimation = animationState.changeType == ChangeType.DISAPPEARED;

        // Disappearing animations are treated differently because we want to keep their animated
        // value up until the point that all animations have finished and we can remove the
        // disappearing content (disappearing items disappear to a value that is based on a provided
        // disappearTo value and not a LayoutOutput, so we can't regenerate it).
        //
        // For non-disappearing content, we know the end value is already reflected by the
        // LayoutOutput we transitioned to, so we don't need to persist an animated value.
        final boolean didFinish;
        if (isDisappearAnimation) {
          final PropertyState propertyState = animationState.propertyStates.get(property);
          if (propertyState == null) {
            throw new RuntimeException(
                "Some animation bookkeeping is wrong: tried to remove an animation from the list "
                    + "of active animations, but it wasn't there.");
          }

          propertyState.numPendingAnimations--;
          didFinish = areAllDisappearingAnimationsFinished(animationState);
          if (didFinish && animationState.mountContentGroup != null) {
            for (AnimatedProperty animatedProperty : animationState.propertyStates.keySet()) {
              resetProperty(animatedProperty, animationState.mountContentGroup);
            }
          }
        } else {
          final PropertyState propertyState = animationState.propertyStates.get(property);
          if (propertyState == null) {
            throw new RuntimeException(
                "Some animation bookkeeping is wrong: tried to remove an animation from the list "
                    + "of active animations, but it wasn't there.");
          }

          propertyState.numPendingAnimations--;
          if (propertyState.numPendingAnimations > 0) {
            didFinish = false;
          } else {
            animationState.propertyStates.remove(property);
            didFinish = animationState.propertyStates.isEmpty();

            if (animationState.mountContentGroup != null) {
              final float value = getPropertyValue(property, animationState.nextLayoutOutputsGroup);
              setPropertyValue(property, value, animationState.mountContentGroup);
            }
          }
        }

        if (didFinish) {
          if (mDebugTag != null) {
            Log.d(mDebugTag, "Finished all animations for transition id " + transitionId);
          }
          if (animationState.mountContentGroup != null) {
            recursivelySetChildClippingForGroup(animationState.mountContentGroup, true);
          }
          if (mOnAnimationCompleteListener != null) {
            mOnAnimationCompleteListener.onAnimationComplete(transitionId);
          }
          removeAnimationState(transitionId, animationFinishTrigger);
          clearLayoutOutputs(animationState);
        }
      }

      final String traceName = mTraceNames.get(binding.hashCode());
      if (!TextUtils.isEmpty(traceName)) {
        mTracer.endAsyncSection(traceName, binding.hashCode());
        mTraceNames.delete(binding.hashCode());
      }
    }

    private boolean areAllDisappearingAnimationsFinished(AnimationState animationState) {
      if (animationState.changeType != ChangeType.DISAPPEARED) {
        throw new RuntimeException("This should only be checked for disappearing animations");
      }
      for (PropertyState propertyState : animationState.propertyStates.values()) {
        if (propertyState.numPendingAnimations > 0) {
          return false;
        }
      }
      return true;
    }
  }

  private void removeAnimationState(
      TransitionId transitionId, AnimationCleanupTrigger animationFinishTrigger) {
    AnimationState animationState = mAnimationStates.get(transitionId);
    mAnimationStates.remove(transitionId);
    trackAnimationStateRemoval(transitionId, animationFinishTrigger, animationState);
  }

  private class TransitionsResolver implements Resolver {

    @Override
    public float getCurrentState(PropertyHandle propertyHandle) {
      final AnimatedProperty animatedProperty = propertyHandle.getProperty();
      final TransitionId transitionId = propertyHandle.getTransitionId();
      final AnimationState animationState = mAnimationStates.get(transitionId);
      final PropertyState propertyState = animationState.propertyStates.get(animatedProperty);

      // Use the current animating value if it exists...
      if (propertyState != null) {
        return propertyState.animatedPropertyNode.getValue();
      }

      // ...otherwise, if it's a property not being animated (e.g., the width when content appears
      // from a width offset), get the property from the LayoutOutput.
      final OutputUnitsAffinityGroup<AnimatableItem> layoutOutputGroupToCheck =
          animationState.changeType == ChangeType.APPEARED
              ? animationState.nextLayoutOutputsGroup
              : animationState.currentLayoutOutputsGroup;
      if (layoutOutputGroupToCheck == null) {
        throw new RuntimeException("Both LayoutOutputs were null!");
      }

      return animatedProperty.get(layoutOutputGroupToCheck.getMostSignificantUnit());
    }

    @Override
    public AnimatedPropertyNode getAnimatedPropertyNode(PropertyHandle propertyHandle) {
      final TransitionId transitionId = propertyHandle.getTransitionId();
      final AnimationState state = mAnimationStates.get(transitionId);
      final PropertyState propertyState = state.propertyStates.get(propertyHandle.getProperty());
      return propertyState.animatedPropertyNode;
    }
  }

  private class RootAnimationListener implements AnimationBindingListener {

    @Override
    public void onScheduledToStartLater(AnimationBinding binding) {}

    @Override
    public void onWillStart(AnimationBinding binding) {
      mRunningRootAnimations.add(binding);
    }

    @Override
    public void onFinish(AnimationBinding binding) {
      mRunningRootAnimations.remove(binding);
    }

    @Override
    public void onCanceledBeforeStart(AnimationBinding binding) {
      mRunningRootAnimations.remove(binding);
    }

    @Override
    public boolean shouldStart(AnimationBinding binding) {
      return true;
    }
  }

  enum AnimationCleanupTrigger {
    CANCELED_BEFORE_START,
    FINISHED_TO_CONCLUSION,
    NON_ANIMATING_CLEANUP,
    UNDECLARED_TRANSITIONS,
    RESET
  }
}
