// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.facebook.rendercore;

import com.facebook.infer.annotation.Nullsafe;
import com.facebook.rendercore.text.TextMeasurementUtils;
import com.facebook.rendercore.text.TextRenderUnit;
import com.facebook.rendercore.text.TextStyle;

@Nullsafe(Nullsafe.Mode.LOCAL)
public class DefaultTextNode extends DefaultNode {

  private final CharSequence mText;
  private final TextStyle mTextStyle;
  private final RenderUnit mRenderUnit;

  public DefaultTextNode(
      YogaProps props, CharSequence text, TextRenderUnit textRenderUnit, TextStyle textStyle) {
    super(props);
    mText = text;
    mTextStyle = textStyle;
    mRenderUnit = textRenderUnit;
  }

  @Override
  public LayoutResult calculateLayout(LayoutContext context, int widthSpec, int heightSpec) {
    return TextMeasurementUtils.layout(
        context, widthSpec, heightSpec, mText, (TextRenderUnit) mRenderUnit, mTextStyle);
  }
}
