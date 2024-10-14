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

package com.facebook.litho.specmodels.generator;

import static com.facebook.litho.specmodels.generator.PreambleGenerator.generateConstructor;
import static org.assertj.core.api.Assertions.assertThat;

import com.facebook.litho.specmodels.internal.ImmutableList;
import com.facebook.litho.specmodels.model.DelegateMethod;
import com.facebook.litho.specmodels.model.SpecMethodModel;
import com.facebook.litho.specmodels.model.SpecModel;
import com.facebook.litho.specmodels.model.SpecModelImpl;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link PreambleGenerator} */
@RunWith(JUnit4.class)
public class PreambleGeneratorTest {
  private static final String TEST_QUALIFIED_SPEC_NAME = "com.facebook.litho.TestSpec";

  private SpecModel mSpecModel;

  @Before
  public void setUp() {
    mSpecModel =
        SpecModelImpl.newBuilder()
            .qualifiedSpecClassName(TEST_QUALIFIED_SPEC_NAME)
            .delegateMethods(ImmutableList.<SpecMethodModel<DelegateMethod, Void>>of())
            .representedObject(new Object())
            .build();
  }

  @Test
  public void testGenerateConstructor() {
    TypeSpecDataHolder typeSpecDataHolder = generateConstructor(mSpecModel);

    assertThat(typeSpecDataHolder.getFieldSpecs()).isEmpty();
    assertThat(typeSpecDataHolder.getMethodSpecs()).hasSize(1);
    assertThat(typeSpecDataHolder.getTypeSpecs()).isEmpty();

    assertThat(typeSpecDataHolder.getMethodSpecs().get(0).toString())
        .isEqualTo("private Constructor() {\n  super(\"Test\");\n" + "}\n");
  }
}
