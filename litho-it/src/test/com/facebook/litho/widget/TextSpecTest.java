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

package com.facebook.litho.widget;

import static androidx.core.text.TextDirectionHeuristicsCompat.FIRSTSTRONG_LTR;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.facebook.litho.testing.MeasureSpecTestingUtilsKt.unspecified;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextUtils.TruncateAt;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.text.TextDirectionHeuristicCompat;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.DynamicValue;
import com.facebook.litho.EventHandler;
import com.facebook.litho.LithoView;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.testing.LithoTestRule;
import com.facebook.litho.testing.eventhandler.EventHandlerTestHelper;
import com.facebook.litho.testing.helper.ComponentTestHelper;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import com.facebook.rendercore.text.TouchableSpanListener;
import com.facebook.yoga.YogaDirection;
import java.util.ArrayList;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests {@link Text} component. */
@RunWith(LithoTestRunner.class)
public class TextSpecTest {
  private ComponentContext mContext;

  private static final int FULL_TEXT_WIDTH = 100;
  private static final int MINIMAL_TEXT_WIDTH = 95;
  private static final String ARABIC_RTL_TEST_STRING =
      "\u0645\u0646 \u0627\u0644\u064A\u0645\u064A\u0646 \u0627\u0644\u0649"
          + " \u0627\u0644\u064A\u0633\u0627\u0631";

  @Rule public final LithoTestRule mLithoTestRule = new LithoTestRule();

  @Before
  public void setup() {
    mContext = new ComponentContext(getApplicationContext());
  }

  private static class TestMountableCharSequence implements MountableCharSequence {

    Drawable mountDrawable;

    @Override
    public void onMount(Drawable parent) {
      mountDrawable = parent;
    }

    @Override
    public void onUnmount(Drawable parent) {}

    @Override
    public int length() {
      return 0;
    }

    @Override
    public char charAt(int index) {
      return 0;
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return null;
    }

    public Drawable getMountDrawable() {
      return mountDrawable;
    }
  }

  @Test
  public void testTextWithoutClickableSpans() {
    TextDrawable drawable = getMountedDrawableForText("Some text.");
    assertThat(drawable.getClickableSpans()).isNull();
  }

  @Test
  public void testSpannableWithoutClickableSpans() {
    Spannable nonClickableText = Spannable.Factory.getInstance().newSpannable("Some text.");

    TextDrawable drawable = getMountedDrawableForText(nonClickableText);
    assertThat(drawable.getClickableSpans()).isNotNull().hasSize(0);
  }

  @Test
  public void testSpannableWithClickableSpans() {
    Spannable clickableText = Spannable.Factory.getInstance().newSpannable("Some text.");
    clickableText.setSpan(
        new ClickableSpan() {
          @Override
          public void onClick(View widget) {}
        },
        0,
        1,
        0);

    TextDrawable drawable = getMountedDrawableForText(clickableText);
    assertThat(drawable.getClickableSpans()).isNotNull().hasSize(1);
  }

  @Test
  public void testSpannableWithClickableSpansGettingCancelEvent() {
    ComponentsConfiguration.enableNewHandleTouchForSpansMethod = true;
    testCancelEventHandling(0, 0, false, false, true);
  }

  @Test
  public void testSpannableWithClickableSpansGettingCancelEventOutsideOfBoundsWithNullSpan() {
    ComponentsConfiguration.enableNewHandleTouchForSpansMethod = true;
    testCancelEventHandling(90000, 900000, false, false, true);
  }

  @Test
  public void testSpannableWithClickableSpansGettingCancelEventWithinBoundsWithNullSpan() {
    ComponentsConfiguration.enableNewHandleTouchForSpansMethod = true;
    testCancelEventHandling(50, 50, false, false, true);
  }

  @Test
  public void
      testSpannableWithClickableSpansGettingCancelEventWithinBoundsWithoutPreviouslyTouchedSpanWillReturnNullSpan() {
    ComponentsConfiguration.enableNewHandleTouchForSpansMethod = true;
    testCancelEventHandling(0, 0, true, true, true);
  }

  private LithoView setupClickableSpanTest(
      String text, ArrayList<Integer> eventsFired, TouchableSpanListener touchableSpanListener) {

    Spannable clickableText = Spannable.Factory.getInstance().newSpannable(text);
    ClickableSpan clickableSpan =
        new ClickableSpan() {
          @Override
          public void onClick(@NonNull View widget) {
            // No action
          }
        };
    clickableText.setSpan(clickableSpan, 0, 1, 0);

    LithoView lithoView =
        ComponentTestHelper.mountComponent(
            mContext,
            Text.create(mContext)
                .text(clickableText)
                .touchableSpanListener(touchableSpanListener)
                .build());

    return lithoView;
  }

  @Test
  public void testSpannableWithClickableSpansGettingUpEventAfterClickingOnSpanAndMovingAway() {
    ComponentsConfiguration.enableNewHandleTouchForSpansMethod = true;
    final ArrayList<Integer> eventsFired = new ArrayList<>();

    TouchableSpanListener touchableSpanListener =
        (span, motionEvent, view) -> {
          eventsFired.add(motionEvent.getAction());
          assertThat(span).isNotNull();
          return true;
        };

    LithoView lithoView = setupClickableSpanTest("Some text.", eventsFired, touchableSpanListener);

    TextDrawable textDrawable = (TextDrawable) (lithoView.getDrawables().get(0));
    // click on the span
    MotionEvent downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
    assertThat(textDrawable.onTouchEvent(downEvent, lithoView)).isEqualTo(true);
    assertThat(eventsFired.size()).isEqualTo(1);
    assertThat(eventsFired).contains(MotionEvent.ACTION_DOWN);
    eventsFired.clear();
    // action up outside of the bounds
    MotionEvent upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 90000, 90000, 0);
    assertThat(textDrawable.onTouchEvent(upEvent, lithoView)).isEqualTo(false);
    assertThat(eventsFired.size()).isEqualTo(0);
  }

  @Test
  public void
      testSpannableWithClickableSpansGettingUpEventAfterClickingOnSpanAndMovingWithinBounds() {
    ComponentsConfiguration.enableNewHandleTouchForSpansMethod = true;
    final ArrayList<Integer> eventsFired = new ArrayList<>();

    TouchableSpanListener touchableSpanListener =
        (span, motionEvent, view) -> {
          eventsFired.add(motionEvent.getAction());
          assertThat(span).isNotNull();
          return true;
        };

    LithoView lithoView =
        setupClickableSpanTest(
            "Some text that is really long and has two spans.", eventsFired, touchableSpanListener);

    TextDrawable textDrawable = (TextDrawable) (lithoView.getDrawables().get(0));
    // click on the span
    MotionEvent downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
    assertThat(textDrawable.onTouchEvent(downEvent, lithoView)).isEqualTo(true);
    assertThat(eventsFired.size()).isEqualTo(1);
    assertThat(eventsFired).contains(MotionEvent.ACTION_DOWN);
    eventsFired.clear();
    // action up within the bounds but on different span
    MotionEvent upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 50, 0);
    assertThat(textDrawable.onTouchEvent(upEvent, lithoView)).isEqualTo(true);
    assertThat(eventsFired.size()).isEqualTo(1);
    assertThat(eventsFired).contains(MotionEvent.ACTION_UP);
  }

  @Test
  public void
      testSpannableWithClickableSpansGettingUpEventAfterClickingOnSpanAndMovingToOtherSpan() {
    ComponentsConfiguration.enableNewHandleTouchForSpansMethod = true;
    final ArrayList<Integer> eventsFired = new ArrayList<>();

    Spannable clickableText = Spannable.Factory.getInstance().newSpannable("Some text.");
    ClickableSpan clickableSpan =
        new ClickableSpan() {
          @Override
          public void onClick(@NonNull View widget) {
            // No action
          }
        };
    ClickableSpan clickableSpan2 =
        new ClickableSpan() {
          @Override
          public void onClick(@NonNull View widget) {
            // No action
          }
        };

    clickableText.setSpan(clickableSpan, 0, 1, 0);
    clickableText.setSpan(clickableSpan2, 2, clickableText.length(), 0);

    TouchableSpanListener touchableSpanListener =
        (span, motionEvent, view) -> {
          eventsFired.add(motionEvent.getAction());
          assertThat(span).isEqualTo(clickableSpan);
          return true;
        };

    LithoView lithoView =
        ComponentTestHelper.mountComponent(
            mContext,
            Text.create(mContext)
                .text(clickableText)
                .touchableSpanListener(touchableSpanListener)
                .build());

    TextDrawable textDrawable = (TextDrawable) (lithoView.getDrawables().get(0));
    // click on the span
    MotionEvent downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
    assertThat(textDrawable.onTouchEvent(downEvent, lithoView)).isEqualTo(true);
    assertThat(eventsFired.size()).isEqualTo(1);
    assertThat(eventsFired).contains(MotionEvent.ACTION_DOWN);
    eventsFired.clear();
    // action up within of the bounds
    MotionEvent upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);
    assertThat(textDrawable.onTouchEvent(upEvent, lithoView)).isEqualTo(true);
    assertThat(eventsFired.size()).isEqualTo(1);
    assertThat(eventsFired).contains(MotionEvent.ACTION_UP);
  }

  @Test
  public void
      testSpannableWithClickableSpansGettingCancelEventAfterClickingOnSpanAndMovingWithinBounds() {
    ComponentsConfiguration.enableNewHandleTouchForSpansMethod = true;
    final ArrayList<Integer> eventsFired = new ArrayList<>();

    TouchableSpanListener touchableSpanListener =
        (span, motionEvent, view) -> {
          eventsFired.add(motionEvent.getAction());
          assertThat(span).isNotNull();
          return true;
        };

    LithoView lithoView =
        setupClickableSpanTest(
            "Some text that is really long and has two spans.", eventsFired, touchableSpanListener);

    TextDrawable textDrawable = (TextDrawable) (lithoView.getDrawables().get(0));
    // click on the span
    MotionEvent downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
    assertThat(textDrawable.onTouchEvent(downEvent, lithoView)).isEqualTo(true);
    assertThat(eventsFired.size()).isEqualTo(1);
    assertThat(eventsFired).contains(MotionEvent.ACTION_DOWN);
    eventsFired.clear();
    // action cancel within the bounds but on different span
    MotionEvent cancelEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 50, 0);
    assertThat(textDrawable.onTouchEvent(cancelEvent, lithoView)).isEqualTo(true);
    assertThat(eventsFired.size()).isEqualTo(1);
    assertThat(eventsFired).contains(MotionEvent.ACTION_CANCEL);
  }

  @Test
  public void
      testSpannableWithClickableSpansGettingCancelEventAfterClickingOnSpanAndMovingOutsideOfBounds() {
    ComponentsConfiguration.enableNewHandleTouchForSpansMethod = true;
    final ArrayList<Integer> eventsFired = new ArrayList<>();

    TouchableSpanListener touchableSpanListener =
        (span, motionEvent, view) -> {
          eventsFired.add(motionEvent.getAction());
          assertThat(span).isNotNull();
          return true;
        };

    LithoView lithoView =
        setupClickableSpanTest(
            "Some text that is really long and has two spans.", eventsFired, touchableSpanListener);

    TextDrawable textDrawable = (TextDrawable) (lithoView.getDrawables().get(0));
    // click on the span
    MotionEvent downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
    assertThat(textDrawable.onTouchEvent(downEvent, lithoView)).isEqualTo(true);
    assertThat(eventsFired.size()).isEqualTo(1);
    assertThat(eventsFired).contains(MotionEvent.ACTION_DOWN);
    eventsFired.clear();
    // action cancel outside of bounds
    MotionEvent cancelEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 90000, 90000, 0);
    assertThat(textDrawable.onTouchEvent(cancelEvent, lithoView)).isEqualTo(false);
    assertThat(eventsFired.size()).isEqualTo(1);
    assertThat(eventsFired).contains(MotionEvent.ACTION_CANCEL);
  }

  private void testCancelEventHandling(
      float x,
      float y,
      boolean expectedHandled,
      boolean expectedReturnValue,
      boolean spanShouldBeNull) {

    final boolean[] cancelEventFired = new boolean[] {false};

    Spannable clickableText = Spannable.Factory.getInstance().newSpannable("Some text.");
    ClickableSpan clickableSpan =
        new ClickableSpan() {
          @Override
          public void onClick(@NonNull View widget) {
            // No action
          }
        };
    clickableText.setSpan(clickableSpan, 0, 1, 0);

    TouchableSpanListener touchableSpanListener =
        (span, motionEvent, view) -> {
          if (motionEvent.getAction() == MotionEvent.ACTION_CANCEL) {
            cancelEventFired[0] = true;
            if (spanShouldBeNull) {
              assertThat(span).isNull();
            } else {
              assertThat(span).isNotNull();
            }
          }
          return expectedHandled;
        };

    LithoView lithoView =
        ComponentTestHelper.mountComponent(
            mContext,
            Text.create(mContext)
                .text(clickableText)
                .touchableSpanListener(touchableSpanListener)
                .build());

    TextDrawable textDrawable = (TextDrawable) (lithoView.getDrawables().get(0));
    MotionEvent cancelEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, x, y, 0);
    assertThat(textDrawable.onTouchEvent(cancelEvent, lithoView)).isEqualTo(expectedReturnValue);
    assertThat(cancelEventFired[0]).isTrue();
  }

  @Test(expected = IllegalStateException.class)
  public void testTextIsRequired() throws Exception {
    Text.create(mContext).build();
  }

  @Test
  public void testMountableCharSequenceText() {
    TestMountableCharSequence testMountableCharSequence = new TestMountableCharSequence();
    assertThat(testMountableCharSequence.getMountDrawable()).isNull();
    TextDrawable drawable = getMountedDrawableForText(testMountableCharSequence);
    assertThat(testMountableCharSequence.getMountDrawable()).isSameAs(drawable);
  }

  @Test
  public void testTouchOffsetChangeHandlerFired() {
    final boolean[] eventFired = new boolean[] {false};
    EventHandler<TextOffsetOnTouchEvent> eventHandler =
        EventHandlerTestHelper.createMockEventHandler(
            TextOffsetOnTouchEvent.class,
            new EventHandlerTestHelper.MockEventHandler<TextOffsetOnTouchEvent, Void>() {
              @Override
              public Void handleEvent(TextOffsetOnTouchEvent event) {
                eventFired[0] = true;
                return null;
              }
            });

    LithoView lithoView =
        ComponentTestHelper.mountComponent(
            mContext,
            Text.create(mContext).text("Some text").textOffsetOnTouchHandler(eventHandler).build());
    TextDrawable textDrawable = (TextDrawable) (lithoView.getDrawables().get(0));
    MotionEvent motionEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
    boolean handled = textDrawable.onTouchEvent(motionEvent, lithoView);
    // We don't consume touch events from TextTouchOffsetChange event
    assertThat(handled).isFalse();
    assertThat(eventFired[0]).isTrue();
  }

  @Test
  public void testTouchOffsetChangeHandlerNotFired() {
    final boolean[] eventFired = new boolean[] {false};
    EventHandler<TextOffsetOnTouchEvent> eventHandler =
        EventHandlerTestHelper.createMockEventHandler(
            TextOffsetOnTouchEvent.class,
            new EventHandlerTestHelper.MockEventHandler<TextOffsetOnTouchEvent, Void>() {
              @Override
              public Void handleEvent(TextOffsetOnTouchEvent event) {
                eventFired[0] = true;
                return null;
              }
            });

    LithoView lithoView =
        ComponentTestHelper.mountComponent(
            mContext,
            Text.create(mContext).text("Text2").textOffsetOnTouchHandler(eventHandler).build());

    TextDrawable textDrawable = (TextDrawable) (lithoView.getDrawables().get(0));

    MotionEvent actionUp = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);
    boolean handledActionUp = textDrawable.onTouchEvent(actionUp, lithoView);
    assertThat(handledActionUp).isFalse();
    assertThat(eventFired[0]).isFalse();

    MotionEvent actionDown = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
    boolean handledActionMove = textDrawable.onTouchEvent(actionDown, lithoView);
    assertThat(handledActionMove).isFalse();
    assertThat(eventFired[0]).isFalse();
  }

  @Test
  public void testColorDefault() {
    TextDrawable drawable = getMountedDrawableForText("Some text");
    assertThat(drawable.getColor()).isEqualTo(Color.BLACK);
  }

  @Test
  public void testColorOverride() {
    int[][] states = {{0}};
    int[] colors = {Color.GREEN};
    ColorStateList colorStateList = new ColorStateList(states, colors);
    TextDrawable drawable =
        getMountedDrawableForTextWithColors("Some text", Color.RED, colorStateList);
    assertThat(drawable.getColor()).isEqualTo(Color.RED);
  }

  @Test
  public void testColor() {
    TextDrawable drawable = getMountedDrawableForTextWithColors("Some text", Color.RED, null);
    assertThat(drawable.getColor()).isEqualTo(Color.RED);
  }

  @Test
  public void testColorStateList() {
    int[][] states = {{0}};
    int[] colors = {Color.GREEN};
    ColorStateList colorStateList = new ColorStateList(states, colors);
    TextDrawable drawable = getMountedDrawableForTextWithColors("Some text", 0, colorStateList);
    assertThat(drawable.getColor()).isEqualTo(Color.GREEN);
  }

  @Test
  public void testColorStateListMultipleStates() {
    ColorStateList colorStateList =
        new ColorStateList(
            new int[][] {
              new int[] {-android.R.attr.state_enabled}, // disabled state
              new int[] {}
            },
            new int[] {Color.RED, Color.GREEN});
    TextDrawable drawable = getMountedDrawableForTextWithColors("Some text", 0, colorStateList);

    // color should fallback to default state
    assertThat(drawable.getColor()).isEqualTo(Color.GREEN);
  }

  private TextDrawable getMountedDrawableForText(CharSequence text) {
    return (TextDrawable)
        ComponentTestHelper.mountComponent(mContext, Text.create(mContext).text(text).build())
            .getDrawables()
            .get(0);
  }

  private TextDrawable getMountedDrawableForTextWithColors(
      CharSequence text, int color, ColorStateList colorStateList) {
    Text.Builder builder = Text.create(mContext).text(text);
    if (color != 0) {
      builder.textColor(color);
    }
    if (colorStateList != null) {
      builder.textColorStateList(colorStateList);
    }
    return (TextDrawable)
        ComponentTestHelper.mountComponent(mContext, builder.build()).getDrawables().get(0);
  }

  @Test
  public void testFullWidthText() {
    final Layout layout = setupWidthTestTextLayout();

    final int resolvedWidth =
        TextComponentSpec.resolveWidth(
            unspecified(), layout, false /* minimallyWide */, 0 /* minimallyWideThreshold */);

    assertEquals(resolvedWidth, FULL_TEXT_WIDTH);
  }

  @Test
  public void testMinimallyWideText() {
    final Layout layout = setupWidthTestTextLayout();

    final int resolvedWidth =
        TextComponentSpec.resolveWidth(
            unspecified(),
            layout,
            true /* minimallyWide */,
            FULL_TEXT_WIDTH - MINIMAL_TEXT_WIDTH - 1 /* minimallyWideThreshold */);

    assertEquals(resolvedWidth, MINIMAL_TEXT_WIDTH);
  }

  @Test
  public void testMinimallyWideThresholdText() {
    final Layout layout = setupWidthTestTextLayout();

    final int resolvedWidth =
        TextComponentSpec.resolveWidth(
            unspecified(),
            layout,
            true /* minimallyWide */,
            FULL_TEXT_WIDTH - MINIMAL_TEXT_WIDTH /* minimallyWideThreshold */);

    assertEquals(resolvedWidth, FULL_TEXT_WIDTH);
  }

  private static Layout setupWidthTestTextLayout() {
    final Layout layout = mock(Layout.class);

    when(layout.getLineCount()).thenReturn(2);
    when(layout.getWidth()).thenReturn(FULL_TEXT_WIDTH);
    when(layout.getLineRight(anyInt())).thenReturn((float) MINIMAL_TEXT_WIDTH);
    when(layout.getLineLeft(anyInt())).thenReturn(0.0f);

    return layout;
  }

  @Test
  public void testTextAlignment_textStart() {
    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.LTR, null, TextAlignment.TEXT_START))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);

    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.RTL, null, TextAlignment.TEXT_START))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);

    // Layout.Alignment.ALIGN_NORMAL is mapped to TextAlignment.TEXT_START
    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.LTR, Layout.Alignment.ALIGN_NORMAL, null))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);

    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.RTL, Layout.Alignment.ALIGN_NORMAL, null))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);
  }

  @Test
  public void testTextAlignment_textEnd() {
    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.LTR, null, TextAlignment.TEXT_END))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);

    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.RTL, null, TextAlignment.TEXT_END))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);

    // Layout.Alignment.ALIGN_OPPOSITE is mapped to TextAlignment.TEXT_END
    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.LTR, Layout.Alignment.ALIGN_OPPOSITE, null))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);

    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.RTL, Layout.Alignment.ALIGN_OPPOSITE, null))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);
  }

  @Test
  public void testTextAlignment_center() {
    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.LTR, null, TextAlignment.CENTER))
        .isEqualTo(Layout.Alignment.ALIGN_CENTER);

    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.RTL, null, TextAlignment.CENTER))
        .isEqualTo(Layout.Alignment.ALIGN_CENTER);

    // Layout.Alignment.ALIGN_CENTER is mapped to TextAlignment.CENTER
    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.LTR, Layout.Alignment.ALIGN_CENTER, null))
        .isEqualTo(Layout.Alignment.ALIGN_CENTER);

    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.RTL, Layout.Alignment.ALIGN_CENTER, null))
        .isEqualTo(Layout.Alignment.ALIGN_CENTER);
  }

  @Test
  public void testTextAlignment_layoutStart() {
    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.LTR, null, TextAlignment.LAYOUT_START))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);

    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.RTL, null, TextAlignment.LAYOUT_START))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);

    assertThat(
            getMountedDrawableLayoutAlignment(
                ARABIC_RTL_TEST_STRING, YogaDirection.LTR, null, TextAlignment.LAYOUT_START))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);

    assertThat(
            getMountedDrawableLayoutAlignment(
                ARABIC_RTL_TEST_STRING, YogaDirection.RTL, null, TextAlignment.LAYOUT_START))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);
  }

  @Test
  public void testTextAlignment_layoutEnd() {
    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.LTR, null, TextAlignment.LAYOUT_END))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);

    assertThat(
            getMountedDrawableLayoutAlignment(
                "asdf", YogaDirection.RTL, null, TextAlignment.LAYOUT_END))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);

    assertThat(
            getMountedDrawableLayoutAlignment(
                ARABIC_RTL_TEST_STRING, YogaDirection.LTR, null, TextAlignment.LAYOUT_END))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);

    assertThat(
            getMountedDrawableLayoutAlignment(
                ARABIC_RTL_TEST_STRING, YogaDirection.RTL, null, TextAlignment.LAYOUT_END))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);
  }

  @Test
  public void testTextAlignment_left() {
    assertThat(
            getMountedDrawableLayoutAlignment("asdf", YogaDirection.LTR, null, TextAlignment.LEFT))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);

    assertThat(
            getMountedDrawableLayoutAlignment("asdf", YogaDirection.RTL, null, TextAlignment.LEFT))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);

    assertThat(
            getMountedDrawableLayoutAlignment(
                ARABIC_RTL_TEST_STRING, YogaDirection.LTR, null, TextAlignment.LEFT))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);

    assertThat(
            getMountedDrawableLayoutAlignment(
                ARABIC_RTL_TEST_STRING, YogaDirection.RTL, null, TextAlignment.LEFT))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);
  }

  @Test
  public void testTextAlignment_right() {
    assertThat(
            getMountedDrawableLayoutAlignment("asdf", YogaDirection.LTR, null, TextAlignment.RIGHT))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);

    assertThat(
            getMountedDrawableLayoutAlignment("asdf", YogaDirection.RTL, null, TextAlignment.RIGHT))
        .isEqualTo(Layout.Alignment.ALIGN_OPPOSITE);

    assertThat(
            getMountedDrawableLayoutAlignment(
                ARABIC_RTL_TEST_STRING, YogaDirection.LTR, null, TextAlignment.RIGHT))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);

    assertThat(
            getMountedDrawableLayoutAlignment(
                ARABIC_RTL_TEST_STRING, YogaDirection.RTL, null, TextAlignment.RIGHT))
        .isEqualTo(Layout.Alignment.ALIGN_NORMAL);
  }

  /* Test for LTR text aligned left. */
  @Test
  @Ignore("T146174263")
  public void testCustomEllipsisTextForLtrShortText() {
    TextDrawable textDrawable =
        getMountedDrawableForTextWithMaxLines(
            "Simple\nSome second line", 1, "Truncate", TextAlignment.LEFT, FIRSTSTRONG_LTR);

    assertEquals(textDrawable.getText().toString(), "SimpleTruncate");
  }

  @Test
  @Ignore("T146174263")
  public void testCustomEllipsisTextForLtrLongText() {
    TextDrawable textDrawable =
        getMountedDrawableForTextWithMaxLines(
            "Simple sentence that should be quite long quite long quite long quite long quite long"
                + " quite long quite long\n"
                + "Some second line",
            1,
            "Truncate",
            TextAlignment.LEFT,
            FIRSTSTRONG_LTR);

    assertEquals(
        textDrawable.getText().toString(),
        "Simple sentence that should be quite long quite long quite long quite long quite long"
            + " quiteTruncate");
  }

  @Test
  @Ignore("T146174263")
  public void testCustomEllipsisTextForLtrShortTextWithShortEllipsis() {
    TextDrawable textDrawable =
        getMountedDrawableForTextWithMaxLines(
            "Simple\nSome second line", 1, ".", TextAlignment.LEFT, FIRSTSTRONG_LTR);

    assertEquals(textDrawable.getText().toString(), "Simple.");
  }

  @Test
  @Ignore("T146174263")
  public void testCustomEllipsisTextForLtrLongTextWithShortEllipsis() {
    TextDrawable textDrawable =
        getMountedDrawableForTextWithMaxLines(
            "Simple sentence that should be quite long quite long quite long quite long quite long"
                + " quite long quite long\n"
                + "Some second line",
            1,
            ".",
            TextAlignment.LEFT,
            FIRSTSTRONG_LTR);

    assertEquals(
        textDrawable.getText().toString(),
        "Simple sentence that should be quite long quite long quite long quite long quite long"
            + " quite long q.");
  }

  /* Test for LTR text aligned right. */
  @Test
  @Ignore("T146174263")
  public void testCustomEllipsisTextForLtrShortTextAlignedRight() {
    TextDrawable textDrawable =
        getMountedDrawableForTextWithMaxLines(
            "Simple\nSome second line", 1, "Truncate", TextAlignment.RIGHT, FIRSTSTRONG_LTR);

    assertEquals(textDrawable.getText().toString(), "SimpleTruncate");
  }

  @Test
  @Ignore("T146174263")
  public void testCustomEllipsisTextForLtrLongTextAlignedRight() {
    TextDrawable textDrawable =
        getMountedDrawableForTextWithMaxLines(
            "Simple sentence that should be quite long quite long quite long quite long quite long"
                + " quite long quite long\n"
                + "Some second line",
            1,
            "Truncate",
            TextAlignment.RIGHT,
            FIRSTSTRONG_LTR);

    assertEquals(
        textDrawable.getText().toString(),
        "Simple sentence that should be quite long quite long quite long quite long quite long"
            + " quiteTruncate");
  }

  @Test
  @Ignore("T146174263")
  public void testCustomEllipsisTextForLtrShortTextWithShortEllipsisAlignedRight() {
    TextDrawable textDrawable =
        getMountedDrawableForTextWithMaxLines(
            "Simple\nSome second line", 1, ".", TextAlignment.RIGHT, FIRSTSTRONG_LTR);

    assertEquals(textDrawable.getText().toString(), "Simple.");
  }

  @Test
  @Ignore("T146174263")
  public void testCustomEllipsisTextForLtrLongTextWithShortEllipsisAlignedRight() {
    TextDrawable textDrawable =
        getMountedDrawableForTextWithMaxLines(
            "Simple sentence that should be quite long quite long quite long quite long quite long"
                + " quite long quite long\n"
                + "Some second line",
            1,
            ".",
            TextAlignment.RIGHT,
            FIRSTSTRONG_LTR);

    assertEquals(
        textDrawable.getText().toString(),
        "Simple sentence that should be quite long quite long quite long quite long quite long"
            + " quite long q.");
  }

  @Test
  public void whenDynamicTextColorIsChanged_TextColorShouldUpdateWithoutReRendering() {

    final DynamicValue<Integer> textColor = new DynamicValue<>(Color.BLUE);

    LithoView lithoView =
        mLithoTestRule
            .render(
                c ->
                    Text.create(c.getContext())
                        .text("hello world")
                        .dynamicTextColor(textColor)
                        .build())
            .getLithoView();

    Object content = lithoView.getMountItemAt(0).getContent();
    assertThat(content).isInstanceOf(TextDrawable.class);
    assertThat(((TextDrawable) content).getLayout().getPaint().getColor()).isEqualTo(Color.BLUE);

    textColor.set(Color.GREEN);
    assertThat(((TextDrawable) content).getLayout().getPaint().getColor()).isEqualTo(Color.GREEN);

    lithoView.setComponentTree(null);

    assertThat(((TextDrawable) content).getLayout()).isNull();
  }

  private TextDrawable getMountedDrawableForTextWithMaxLines(
      CharSequence text,
      int maxLines,
      String customEllipsisText,
      TextAlignment alignment,
      TextDirectionHeuristicCompat textDirection) {
    return (TextDrawable)
        ComponentTestHelper.mountComponent(
                mContext,
                Text.create(mContext)
                    .ellipsize(TruncateAt.END)
                    .textDirection(textDirection)
                    .text(text)
                    .alignment(alignment)
                    .maxLines(maxLines)
                    .customEllipsisText(customEllipsisText)
                    .build())
            .getDrawables()
            .get(0);
  }

  private Layout.Alignment getMountedDrawableLayoutAlignment(
      String text,
      @Nullable YogaDirection layoutDirection,
      @Nullable Layout.Alignment deprecatedTextAlignment,
      @Nullable TextAlignment textAlignment) {

    Text.Builder builder = Text.create(mContext).text(text);

    if (layoutDirection != null) {
      builder.layoutDirection(layoutDirection);
    }

    if (deprecatedTextAlignment != null) {
      builder.textAlignment(deprecatedTextAlignment);
    }

    if (textAlignment != null) {
      builder.alignment(textAlignment);
    }

    return ((TextDrawable)
            ComponentTestHelper.mountComponent(mContext, builder.build()).getDrawables().get(0))
        .getLayoutAlignment();
  }
}
