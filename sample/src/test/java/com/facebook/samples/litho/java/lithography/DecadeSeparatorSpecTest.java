// (c) Facebook, Inc. and its affiliates. Confidential and proprietary.

/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.samples.litho.java.lithography;

import static com.facebook.litho.testing.assertj.LegacyLithoAssertions.assertThat;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assume.assumeThat;

import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.config.LithoDebugConfigurations;
import com.facebook.litho.testing.LithoTestRule;
import com.facebook.litho.testing.TestLithoView;
import com.facebook.litho.testing.assertj.LithoAssertions;
import com.facebook.litho.testing.subcomponents.InspectableComponent;
import com.facebook.litho.testing.subcomponents.SubComponent;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import com.facebook.litho.widget.Text;
import org.assertj.core.api.Condition;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(LithoTestRunner.class)
public class DecadeSeparatorSpecTest {
  @Rule public LithoTestRule lithoTestRule = new LithoTestRule();

  private Component mComponent;

  @Before
  public void setUp() {
    assumeThat(
        "These tests can only be run in debug mode.",
        LithoDebugConfigurations.isDebugModeEnabled,
        is(true));
    mComponent =
        DecadeSeparator.create(lithoTestRule.getContext()).decade(new Decade(2010)).build();
  }

  @Test
  public void subComponentsWithManualExtraction() {
    final ComponentContext c = lithoTestRule.getContext();

    assertThat(c, mComponent).extractingSubComponentAt(0).extractingSubComponents(c).hasSize(3);
  }

  @Test
  public void testSubComponentByClass() {
    final ComponentContext c = lithoTestRule.getContext();
    assertThat(c, mComponent).hasSubComponents(SubComponent.of(Text.class));
  }

  @Test
  public void subComponentByClassWithExtraction() {
    final ComponentContext c = lithoTestRule.getContext();
    assertThat(c, mComponent)
        .extractingSubComponentAt(0)
        .extractingSubComponents(c)
        .areExactly(
            1,
            new Condition<InspectableComponent>() {
              @Override
              public boolean matches(InspectableComponent value) {
                return value.getComponentClass() == Text.class;
              }
            });
  }

  @Test
  public void subComponentWithText() {
    final ComponentContext c = lithoTestRule.getContext();
    TestLithoView testLithoView = lithoTestRule.render(componentScope -> mComponent);
    LithoAssertions.assertThat(testLithoView)
        .hasVisibleText("2010")
        .doesNotHaveVisibleText("2011")
        .hasVisibleTextMatching("10");
  }
}
