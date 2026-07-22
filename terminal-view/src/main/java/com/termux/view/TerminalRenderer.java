package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.util.SparseArray;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import java.util.HashMap;

public final class TerminalRenderer {

    private static final int CLS_FIT = 0;
    private static final int CLS_NATURAL = 1;
    private static final int CLS_NORMAL = 2;
    private static final int CLS_TEXT_FIT = 3;

    private static final int PAINT_TEXT = 0;
    private static final int PAINT_EMOJI = 1;

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();
    private final Paint mEmojiPaint = new Paint();

    final float mFontWidth;
    final int mFontLineSpacing;
    private final int mFontAscent;
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    private final SparseArray<Float> mTextCodePointWidths = new SparseArray<>();
    private final SparseArray<Float> mEmojiCodePointWidths = new SparseArray<>();
    private final HashMap<String, GlyphMetric> mSequenceMetrics = new HashMap<>();
    private final HashMap<Integer, Float> mInkWidths = new HashMap<>();

    private final Rect mTempRect = new Rect();
    private final Paint.FontMetrics mTempFontMetrics = new Paint.FontMetrics();

    private static final class GlyphMetric {
        final float advance;
        final int paintId;
        final float inkLeft, inkTop, inkRight, inkBottom;

        GlyphMetric(float advance, int paintId, Rect bounds) {
            this.advance = advance;
            this.paintId = paintId;
            this.inkLeft = bounds.left;
            this.inkTop = bounds.top;
            this.inkRight = bounds.right;
            this.inkBottom = bounds.bottom;
        }

        float inkWidth() { return inkRight - inkLeft; }
        float inkHeight() { return inkBottom - inkTop; }
        float inkCenterX() { return (inkLeft + inkRight) / 2.f; }
        float inkCenterY() { return (inkTop + inkBottom) / 2.f; }
    }

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        Typeface emojiTf;
        try {
            emojiTf = Typeface.create("sans-serif-emoji", Typeface.NORMAL);
        } catch (Exception e) {
            emojiTf = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
        }
        mEmojiPaint.setTypeface(emojiTf);
        mEmojiPaint.setAntiAlias(true);
        mEmojiPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    private static int classify(int codePoint, boolean vs16Forced, boolean vs15Forced) {
        if (vs16Forced) return CLS_FIT;
        if (vs15Forced) return CLS_NORMAL;
        if (EmojiData.isEmojiPresentation(codePoint)) return CLS_FIT;
        if ((codePoint >= 0x2500 && codePoint <= 0x257F) ||
            (codePoint >= 0x2580 && codePoint <= 0x259F) ||
            (codePoint >= 0x25A0 && codePoint <= 0x25FF) ||
            (codePoint >= 0x2190 && codePoint <= 0x21FF) ||
            (codePoint >= 0x2200 && codePoint <= 0x22FF)) {
            return CLS_NATURAL;
        }
        return CLS_NORMAL;
    }

    private static boolean isClusterExtender(int codePoint) {
        if (codePoint == 0x200D) return true;
        if (codePoint == 0xFE0F || codePoint == 0xFE0E) return true;
        if (codePoint == 0x20E3) return true;
        if (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF) return true;
        if (codePoint >= 0xE0020 && codePoint <= 0xE007F) return true;
        if (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF) return true;
        return false;
    }

    private static int consumeCluster(char[] line, int charIndex, int limit) {
        if (charIndex >= limit) return 0;

        int idx = charIndex;
        int cp = codePointAt(line, idx, limit);
        int cpLen = Character.charCount(cp);
        idx += cpLen;

        if (cp >= 0x1F1E6 && cp <= 0x1F1FF) {
            if (idx < limit) {
                int cp2 = codePointAt(line, idx, limit);
                if (cp2 >= 0x1F1E6 && cp2 <= 0x1F1FF) {
                    idx += Character.charCount(cp2);
                }
            }
            if (idx < limit) {
                int cp3 = codePointAt(line, idx, limit);
                if (cp3 == 0xFE0F || cp3 == 0xFE0E) {
                    idx += Character.charCount(cp3);
                }
            }
            return idx - charIndex;
        }

        while (idx < limit) {
            int nextCp = codePointAt(line, idx, limit);
            int nextLen = Character.charCount(nextCp);
            if (nextCp == 0x200D) {
                idx += nextLen;
                if (idx < limit) {
                    int afterZwj = codePointAt(line, idx, limit);
                    idx += Character.charCount(afterZwj);
                }
            } else if (nextCp == 0xFE0F || nextCp == 0xFE0E) {
                idx += nextLen;
            } else if (nextCp >= 0x1F3FB && nextCp <= 0x1F3FF) {
                idx += nextLen;
            } else if (nextCp == 0x20E3) {
                idx += nextLen;
            } else if (nextCp >= 0xE0020 && nextCp <= 0xE007F) {
                idx += nextLen;
            } else {
                break;
            }
        }

        return idx - charIndex;
    }

    private static int lastCodePointInCluster(char[] line, int start, int len) {
        if (len <= 0) return 0;
        int end = start + len;
        if (end >= 2 && Character.isLowSurrogate(line[end - 1]) && Character.isHighSurrogate(line[end - 2])) {
            return Character.toCodePoint(line[end - 2], line[end - 1]);
        }
        return line[end - 1];
    }

    private static int codePointAt(char[] chars, int index, int limit) {
        char c = chars[index];
        if (Character.isHighSurrogate(c) && (index + 1) < limit && Character.isLowSurrogate(chars[index + 1])) {
            return Character.toCodePoint(c, chars[index + 1]);
        }
        return c;
    }

    private float measureWithPaint(Paint paint, char[] text, int start, int len) {
        if (Build.VERSION.SDK_INT >= 23) {
            return paint.getRunAdvance(text, start, start + len, start, start + len, false, start + len);
        } else {
            return paint.measureText(text, start, len);
        }
    }

    private float measureCodePointText(int codePoint) {
        Float cached = mTextCodePointWidths.get(codePoint);
        if (cached != null) return cached;
        char[] chars = Character.toChars(codePoint);
        resetPaintEffects(mTextPaint);
        float w = measureWithPaint(mTextPaint, chars, 0, chars.length);
        mTextCodePointWidths.put(codePoint, w);
        return w;
    }

    @SuppressWarnings("unused")
    private float measureCodePointEmoji(int codePoint) {
        Float cached = mEmojiCodePointWidths.get(codePoint);
        if (cached != null) return cached;
        char[] chars = Character.toChars(codePoint);
        float w = measureWithPaint(mEmojiPaint, chars, 0, chars.length);
        mEmojiCodePointWidths.put(codePoint, w);
        return w;
    }

    /** Returns the ink (glyph bounding box) width for a single code point using mTextPaint. */
    private float getInkWidth(int codePoint) {
        Float cached = mInkWidths.get(codePoint);
        if (cached != null) return cached;
        char[] chars = Character.toChars(codePoint);
        resetPaintEffects(mTextPaint);
        mTextPaint.getTextBounds(chars, 0, chars.length, mTempRect);
        float w = mTempRect.width();
        // getTextBounds returns 0 when primary typeface lacks glyph.
        // Fall back to advance as an estimate.
        if (w <= 0) {
            w = mTextPaint.measureText(chars, 0, chars.length);
        }
        mInkWidths.put(codePoint, w);
        return w;
    }

    private GlyphMetric measureCluster(char[] line, int start, int len, Paint paint, int paintId) {
        String key = paintId + ":" + new String(line, start, len);
        GlyphMetric cached = mSequenceMetrics.get(key);
        if (cached != null) return cached;

        resetPaintEffects(paint);
        float advance = measureWithPaint(paint, line, start, len);
        paint.getTextBounds(line, start, len, mTempRect);
        GlyphMetric metric = new GlyphMetric(advance, paintId, mTempRect);
        mSequenceMetrics.put(key, metric);
        return metric;
    }

    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);
        else
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_BACKGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;
            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                if (currentCharIndex >= charsUsedInLine) break;

                final int codePoint = codePointAt(line, currentCharIndex, charsUsedInLine);
                final int cpCharLen = Character.charCount(codePoint);

                final int clusterLen = consumeCluster(line, currentCharIndex, charsUsedInLine);
                final boolean isMultiCpCluster = (clusterLen > cpCharLen);

                final int codePointWcWidth;
                if (isMultiCpCluster) {
                    codePointWcWidth = 2;
                } else {
                    int w = WcWidth.width(codePoint);
                    codePointWcWidth = (w <= 0) ? 1 : w;
                }

                final boolean insideCursor = (cursorX == column ||
                        (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = (selx1 >= 0 && column >= selx1 && column <= selx2);
                final long style = lineObject.getStyle(column);

                final boolean hasVS16, hasVS15;
                if (clusterLen > 0 && isMultiCpCluster) {
                    int lastCp = lastCodePointInCluster(line, currentCharIndex, clusterLen);
                    hasVS16 = (lastCp == 0xFE0F);
                    hasVS15 = (lastCp == 0xFE0E);
                } else {
                    hasVS16 = false;
                    hasVS15 = false;
                }

                final int originalCls;
                if (isMultiCpCluster) {
                    originalCls = CLS_FIT;
                } else {
                    originalCls = classify(codePoint, hasVS16, hasVS15);
                }

                final float measuredWidth;
                if (originalCls == CLS_FIT) {
                    GlyphMetric gm = measureCluster(line, currentCharIndex, clusterLen, mEmojiPaint, PAINT_EMOJI);
                    measuredWidth = gm.advance;
                } else if (codePoint < asciiMeasures.length && clusterLen == 1) {
                    measuredWidth = asciiMeasures[codePoint];
                } else {
                    measuredWidth = measureCodePointText(codePoint);
                }

                final boolean fontWidthMismatch =
                        Math.abs(measuredWidth / mFontWidth - codePointWcWidth) > 0.01;

                final boolean uniformNormalOverride = (originalCls == CLS_NORMAL
                    && !isMultiCpCluster && codePoint > 0x7F && fontWidthMismatch
                    && !hasVS15
                    && !isTextLike(codePoint));

                final int cls = uniformNormalOverride ? CLS_FIT : originalCls;

                // CLS_TEXT_FIT: for text-like CLS_NORMAL characters whose ink is
                // significantly narrower than the cell. Advance matches cell width
                // but glyph doesn't fill it.
                int effectiveCls = cls;
                if (cls == CLS_NORMAL && !isMultiCpCluster && codePoint > 0x7F
                        && !hasVS15 && clusterLen == 1
                        && isNarrowEnclosedSymbol(codePoint)) {
                    float inkW = getInkWidth(codePoint);
                    float cellW = codePointWcWidth * mFontWidth;
                    // Apply TEXT_FIT when ink is 0 (font lacks glyph) or narrower than 85% of cell
                    if (inkW <= 0 || inkW < cellW * 0.85f) {
                        effectiveCls = CLS_TEXT_FIT;
                    }
                }

                final boolean forceBreak = (effectiveCls != CLS_NORMAL);

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor ||
                    insideSelection != lastRunInsideSelection ||
                    fontWidthMismatch || lastRunFontWidthMismatch || forceBreak) {
                    if (lastRunStartColumn >= 0 && column > lastRunStartColumn) {
                        flushNormalRun(canvas, line, palette, heightOffset,
                                lastRunStartColumn, column - lastRunStartColumn,
                                lastRunStartIndex, currentCharIndex - lastRunStartIndex,
                                measuredWidthForRun, lastRunInsideCursor, lastRunInsideSelection,
                                cursorShape, lastRunStyle, reverseVideo, mEmulator);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }

                if (effectiveCls == CLS_FIT) {
                    Paint paint = uniformNormalOverride ? mTextPaint : mEmojiPaint;
                    int paintId = uniformNormalOverride ? PAINT_TEXT : PAINT_EMOJI;
                    drawUniformCell(canvas, line, currentCharIndex, clusterLen, palette,
                            heightOffset, column, codePointWcWidth, style,
                            insideCursor, insideSelection, cursorShape, reverseVideo, mEmulator,
                            paint, paintId);

                    lastRunStartColumn = column + codePointWcWidth;
                    lastRunStartIndex = currentCharIndex + clusterLen;
                    measuredWidthForRun = 0.f;
                    lastRunStyle = 0;
                    lastRunInsideCursor = false;
                    lastRunInsideSelection = false;
                    lastRunFontWidthMismatch = false;
                } else if (effectiveCls == CLS_NATURAL) {
                    drawNaturalCell(canvas, line, currentCharIndex, clusterLen, palette,
                            heightOffset, column, codePointWcWidth, style,
                            insideCursor, insideSelection, cursorShape, reverseVideo, mEmulator);

                    lastRunStartColumn = column + codePointWcWidth;
                    lastRunStartIndex = currentCharIndex + clusterLen;
                    measuredWidthForRun = 0.f;
                    lastRunStyle = 0;
                    lastRunInsideCursor = false;
                    lastRunInsideSelection = false;
                    lastRunFontWidthMismatch = false;
                } else if (effectiveCls == CLS_TEXT_FIT) {
                    drawTextFitCell(canvas, line, currentCharIndex, clusterLen, palette,
                            heightOffset, column, codePointWcWidth, style,
                            insideCursor, insideSelection, cursorShape, reverseVideo, mEmulator);

                    lastRunStartColumn = column + codePointWcWidth;
                    lastRunStartIndex = currentCharIndex + clusterLen;
                    measuredWidthForRun = 0.f;
                    lastRunStyle = 0;
                    lastRunInsideCursor = false;
                    lastRunInsideSelection = false;
                    lastRunFontWidthMismatch = false;
                } else {
                    measuredWidthForRun += measuredWidth;
                }

                column += codePointWcWidth;
                currentCharIndex += clusterLen;

                while (currentCharIndex < charsUsedInLine) {
                    int zcp = codePointAt(line, currentCharIndex, charsUsedInLine);
                    if (isClusterExtender(zcp)) break;
                    if (WcWidth.width(zcp) > 0) break;
                    currentCharIndex += Character.charCount(zcp);
                }
            }

            if (lastRunStartColumn >= 0 && lastRunStartColumn < columns) {
                int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                if (charsSinceLastRun > 0) {
                    flushNormalRun(canvas, line, palette, heightOffset,
                            lastRunStartColumn, columns - lastRunStartColumn,
                            lastRunStartIndex, charsSinceLastRun,
                            measuredWidthForRun, lastRunInsideCursor, lastRunInsideSelection,
                            cursorShape, lastRunStyle, reverseVideo, mEmulator);
                }
            }
        }
    }

    private void flushNormalRun(Canvas canvas, char[] line, int[] palette, float y,
                                int startColumn, int runWidthColumns,
                                int startCharIndex, int runWidthChars,
                                float measuredWidth, boolean insideCursor, boolean insideSelection,
                                int cursorShape, long style, boolean reverseVideo,
                                TerminalEmulator emulator) {
        int cursorColor = insideCursor ? emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
        boolean invertCursorTextColor = insideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
        boolean rev = reverseVideo || invertCursorTextColor || insideSelection;
        drawTextRun(canvas, line, palette, y, startColumn, runWidthColumns,
                startCharIndex, runWidthChars, measuredWidth,
                cursorColor, cursorShape, style, rev);
    }

    private void drawUniformCell(Canvas canvas, char[] line, int start, int len,
                                 int[] palette, float y, int startColumn, int cellCols,
                                 long style, boolean insideCursor, boolean insideSelection,
                                 int cursorShape, boolean reverseVideo, TerminalEmulator emulator,
                                 Paint paint, int paintId) {
        int foreColor = TextStyle.decodeForeColor(style);
        int effect = TextStyle.decodeEffect(style);
        int backColor = TextStyle.decodeBackColor(style);

        boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }
        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        boolean reverseVideoHere = reverseVideo ^ ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0);
        if (insideSelection) reverseVideoHere = true;
        if (insideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
            reverseVideoHere = true;
        }
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float cellLeft = startColumn * mFontWidth;
        float cellRight = cellLeft + cellCols * mFontWidth;
        float cellTop = y - mFontLineSpacing;
        float cellBottom = y;

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            mTextPaint.setColor(backColor);
            resetPaintEffects(mTextPaint);
            canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, mTextPaint);
        }

        if (insideCursor) {
            int cursorColor = emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR];
            mTextPaint.setColor(cursorColor);
            resetPaintEffects(mTextPaint);

            float cLeft = cellLeft;
            float cRight = cellRight;
            float cTop = cellTop;
            float cBottom = cellBottom;

            if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                cTop = cellBottom - (cellBottom - cellTop) / 4.f;
            } else if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
                cRight = cLeft + (cRight - cLeft) / 4.f;
            }

            canvas.drawRect(cLeft, cTop, cRight, cBottom, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0) return;
        if (dim) foreColor = applyDim(foreColor);

        GlyphMetric gm = measureCluster(line, start, len, paint, paintId);

        paint.setFakeBoldText(paintId == PAINT_TEXT && bold);
        paint.setUnderlineText(underline);
        paint.setTextSkewX(italic ? -0.35f : 0.f);
        paint.setStrikeThruText(strikeThrough);
        paint.setColor(foreColor);

        float inkW = gm.inkWidth();
        float inkH = gm.inkHeight();

        float cellW = cellCols * mFontWidth;
        float cellH = mFontLineSpacing;
        float cellCenterX = cellLeft + cellW / 2.f;
        float cellCenterY = cellTop + cellH / 2.f;

        if (inkW <= 0 || inkH <= 0) {
            float advance = measureWithPaint(paint, line, start, len);
            paint.getFontMetrics(mTempFontMetrics);

            float fontHeight = mTempFontMetrics.descent - mTempFontMetrics.ascent;
            if (advance <= 0.f) advance = cellW;
            if (fontHeight <= 0.f) fontHeight = cellH;

            float s = Math.min(cellW / advance, cellH / fontHeight);
            if (s > 1.f) s = 1.f;

            float baselineCenterOffset = (mTempFontMetrics.ascent + mTempFontMetrics.descent) / 2.f;

            canvas.save();
            canvas.translate(cellCenterX, cellCenterY);
            canvas.scale(s, s);
            canvas.drawTextRun(line, start, len, start, len,
                    -advance / 2.f, -baselineCenterOffset, false, paint);
            canvas.restore();
            return;
        }

        float s = Math.min(cellW / inkW, cellH / inkH);
        if (s > 1.f) s = 1.f;

        float inkCX = gm.inkCenterX();
        float inkCY = gm.inkCenterY();

        canvas.save();
        canvas.translate(cellCenterX, cellCenterY);
        canvas.scale(s, s);
        canvas.drawTextRun(line, start, len, start, len, -inkCX, -inkCY, false, paint);
        canvas.restore();
    }

    private void drawNaturalCell(Canvas canvas, char[] line, int start, int len,
                                 int[] palette, float y, int startColumn, int cellCols,
                                 long style, boolean insideCursor, boolean insideSelection,
                                 int cursorShape, boolean reverseVideo, TerminalEmulator emulator) {
        int foreColor = TextStyle.decodeForeColor(style);
        int effect = TextStyle.decodeEffect(style);
        int backColor = TextStyle.decodeBackColor(style);

        boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }
        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        boolean reverseVideoHere = reverseVideo ^ ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0);
        if (insideSelection) reverseVideoHere = true;
        if (insideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
            reverseVideoHere = true;
        }
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float cellLeft = startColumn * mFontWidth;
        float cellRight = cellLeft + cellCols * mFontWidth;
        float cellTop = y - mFontLineSpacing;
        float cellBottom = y;

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            mTextPaint.setColor(backColor);
            resetPaintEffects(mTextPaint);
            canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, mTextPaint);
        }

        if (insideCursor) {
            int cursorColor = emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR];
            mTextPaint.setColor(cursorColor);
            resetPaintEffects(mTextPaint);

            float cLeft = cellLeft;
            float cRight = cellRight;
            float cTop = cellTop;
            float cBottom = cellBottom;

            if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                cTop = cellBottom - (cellBottom - cellTop) / 4.f;
            } else if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
                cRight = cLeft + (cRight - cLeft) / 4.f;
            }

            canvas.drawRect(cLeft, cTop, cRight, cBottom, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0) return;
        if (dim) foreColor = applyDim(foreColor);

        mTextPaint.setFakeBoldText(bold);
        mTextPaint.setUnderlineText(underline);
        mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
        mTextPaint.setStrikeThruText(strikeThrough);
        mTextPaint.setColor(foreColor);

        float baselineY = y - mFontLineSpacingAndAscent;
        canvas.drawTextRun(line, start, len, start, len, cellLeft, baselineY, false, mTextPaint);
    }

    /**
     * Renders a text-like symbol (circled number, Roman numeral) so its ink
     * fills the cell while preserving aspect ratio as much as possible.
     * Unlike drawUniformCell: uses mTextPaint, allows upscale.
     * Unlike drawTextRun: scales by ink bounds, not advance width.
     */
    private void drawTextFitCell(Canvas canvas, char[] line, int start, int len,
                                 int[] palette, float y, int startColumn, int cellCols,
                                 long style, boolean insideCursor, boolean insideSelection,
                                 int cursorShape, boolean reverseVideo, TerminalEmulator emulator) {
        int foreColor = TextStyle.decodeForeColor(style);
        int effect = TextStyle.decodeEffect(style);
        int backColor = TextStyle.decodeBackColor(style);
        boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        if ((foreColor & 0xff000000) != 0xff000000) {
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }
        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }
        boolean reverseVideoHere = reverseVideo ^ ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0);
        if (insideSelection) reverseVideoHere = true;
        if (insideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
            reverseVideoHere = true;
        }
        if (reverseVideoHere) {
            int tmp = foreColor; foreColor = backColor; backColor = tmp;
        }

        float cellLeft = startColumn * mFontWidth;
        float cellRight = cellLeft + cellCols * mFontWidth;
        float cellTop = y - mFontLineSpacing;
        float cellBottom = y;
        float cellW = cellCols * mFontWidth;
        float cellH = mFontLineSpacing;

        // Background
        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            mTextPaint.setColor(backColor);
            resetPaintEffects(mTextPaint);
            canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, mTextPaint);
        }

        // Cursor
        if (insideCursor) {
            int cursorColor = emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR];
            mTextPaint.setColor(cursorColor);
            resetPaintEffects(mTextPaint);
            float cLeft = cellLeft, cRight = cellRight, cTop = cellTop, cBottom = cellBottom;
            if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                cTop = cellBottom - (cellBottom - cellTop) / 4.f;
            } else if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
                cRight = cLeft + (cRight - cLeft) / 4.f;
            }
            canvas.drawRect(cLeft, cTop, cRight, cBottom, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0) return;
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) foreColor = applyDim(foreColor);

        // Measure ink using mTextPaint (monospace + fallback)
        GlyphMetric gm = measureCluster(line, start, len, mTextPaint, PAINT_TEXT);
        float inkW = gm.inkWidth();
        float inkH = gm.inkHeight();
        float inkCX, inkCY;

        if (inkW <= 0 || inkH <= 0) {
            float advance = gm.advance;
            mTextPaint.getFontMetrics(mTempFontMetrics);
            float ascent = Math.abs(mTempFontMetrics.ascent);
            float descent = mTempFontMetrics.descent;
            float fontH = ascent + descent;

            inkW = (advance > 0) ? advance : cellW * 0.6f;
            inkH = (fontH > 0) ? fontH : cellH * 0.7f;

            // Synthesized ink center
            inkCX = inkW * 0.5f;
            inkCY = -ascent + inkH * 0.5f;
        } else {
            inkCX = gm.inkCenterX();
            inkCY = gm.inkCenterY();
        }

        // Compute uniform scale to fill cell
        float scaleX = cellW / inkW;
        float scaleY = cellH / inkH;
        float s = Math.min(scaleX, scaleY);  // uniform: preserve aspect ratio

        // Allow upscale (these glyphs are meant to fill the cell) but cap at 2x
        if (s > 2.0f) s = 2.0f;
        if (s < 1.0f) s = 1.0f;  // never shrink below natural size

        // If after uniform scaling the ink still doesn't fill width,
        // apply additional horizontal stretch (gentle oval)
        float hStretch = 1.0f;
        float effectiveInkW = inkW * s;
        if (effectiveInkW < cellW * 0.92f) {
            hStretch = cellW / effectiveInkW;
            if (hStretch > 1.5f) hStretch = 1.5f;
        }

        // Draw with transform
        mTextPaint.setFakeBoldText(bold);
        mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
        mTextPaint.setColor(foreColor);

        float cellCenterX = cellLeft + cellW / 2.f;
        float cellCenterY = cellTop + cellH / 2.f;

        canvas.save();
        canvas.translate(cellCenterX, cellCenterY);
        canvas.scale(s * hStretch, s);
        canvas.drawTextRun(line, start, len, start, len, -inkCX, -inkCY, false, mTextPaint);
        canvas.restore();
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y,
                             int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes,
                             int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);

        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }
        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        final boolean reverseVideoHere = reverseVideo ^ ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0);
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        final float cellLeft = startColumn * mFontWidth;
        final float cellRight = cellLeft + runWidthColumns * mFontWidth;
        final float cellTop = y - mFontLineSpacing;
        final float cellBottom = y;
        final float baselineY = y - mFontLineSpacingAndAscent;

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            mTextPaint.setColor(backColor);
            resetPaintEffects(mTextPaint);
            canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            resetPaintEffects(mTextPaint);

            float cLeft = cellLeft;
            float cRight = cellRight;
            float cTop = cellTop;
            float cBottom = cellBottom;

            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                cTop = cellBottom - (cellBottom - cellTop) / 4.f;
            } else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
                cRight = cLeft + (cRight - cLeft) / 4.f;
            }

            canvas.drawRect(cLeft, cTop, cRight, cBottom, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0) return;
        if (dim) foreColor = applyDim(foreColor);

        mTextPaint.setFakeBoldText(bold);
        mTextPaint.setUnderlineText(underline);
        mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
        mTextPaint.setStrikeThruText(strikeThrough);
        mTextPaint.setColor(foreColor);

        float naturalWidthRatio = mes / mFontWidth;
        if (Math.abs(naturalWidthRatio - runWidthColumns) > 0.01 && mes > 0) {
            float scaleFactor = (runWidthColumns * mFontWidth) / mes;
            canvas.save();
            canvas.translate(cellLeft, 0.f);
            canvas.scale(scaleFactor, 1.f);
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars,
                    0.f, baselineY, false, mTextPaint);
            canvas.restore();
        } else {
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars,
                    cellLeft, baselineY, false, mTextPaint);
        }
    }

    private static boolean isNarrowEnclosedSymbol(int cp) {
        // Enclosed Alphanumerics (U+2460-U+24FF): circled numbers/letters
        if (cp >= 0x2460 && cp <= 0x24FF) return true;
        // Number Forms (U+2150-U+218F): Roman numerals Ⅰ-Ⅻ, ⅰ-ⅻ, fractions
        if (cp >= 0x2150 && cp <= 0x218F) return true;
        // Dingbat circled numbers (U+2776-U+2793): ❶-❿, ➊-➓, ➀-➉
        if (cp >= 0x2776 && cp <= 0x2793) return true;
        // Enclosed CJK Letters and Months (U+3200-U+32FF)
        if (cp >= 0x3200 && cp <= 0x32FF) return true;
        // Enclosed Alphanumeric Supplement (U+1F100-U+1F1FF)
        if (cp >= 0x1F100 && cp <= 0x1F1FF) return true;
        // Enclosed Ideographic Supplement (U+1F200-U+1F2FF)
        if (cp >= 0x1F200 && cp <= 0x1F2FF) return true;
        return false;
    }

    private static boolean isTextLike(int cp) {
        final int type = Character.getType(cp);

        // Unicode general categories that should be treated as text-like:
        // L*: all letters, Nd: decimal digits, Nl: Roman numerals, No: circled digits/fractions
        switch (type) {
            case Character.UPPERCASE_LETTER:
            case Character.LOWERCASE_LETTER:
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            case Character.LETTER_NUMBER:
            case Character.DECIMAL_DIGIT_NUMBER:
            case Character.OTHER_NUMBER:
                return true;
        }

        // Enclosed alphanumeric symbols that Unicode classifies as So (Other_Symbol)
        // but are actually text-like, not emoji.
        if (cp >= 0x2460 && cp <= 0x24FF) return true;  // Enclosed Alphanumerics
        if (cp >= 0x3200 && cp <= 0x32FF) return true;  // Enclosed CJK Letters and Months
        if (cp >= 0x2776 && cp <= 0x2793) return true;  // Dingbat circled numbers
        if (cp >= 0x1F100 && cp <= 0x1F10C) return true; // Negative circled numbers

        // Keep explicit CJK/Hangul/Kana ranges for safety (redundant but harmless)
        if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
        if (cp >= 0x3400 && cp <= 0x4DBF) return true;
        if (cp >= 0x20000 && cp <= 0x2A6DF) return true;
        if (cp >= 0x2A700 && cp <= 0x2B73F) return true;
        if (cp >= 0x2B740 && cp <= 0x2B81F) return true;
        if (cp >= 0x2B820 && cp <= 0x2CEAF) return true;
        if (cp >= 0x2CEB0 && cp <= 0x2EBE0) return true;
        if (cp >= 0x30000 && cp <= 0x3134F) return true;
        if (cp >= 0x31350 && cp <= 0x323AF) return true;
        if (cp >= 0xAC00 && cp <= 0xD7A3) return true;
        if (cp >= 0x1100 && cp <= 0x11FF) return true;
        if (cp >= 0xA960 && cp <= 0xA97C) return true;
        if (cp >= 0xD7B0 && cp <= 0xD7FF) return true;
        if (cp >= 0x3041 && cp <= 0x3096) return true;
        if (cp >= 0x30A1 && cp <= 0x30FA) return true;

        return false;
    }

    private static void resetPaintEffects(Paint p) {
        p.setFakeBoldText(false);
        p.setUnderlineText(false);
        p.setTextSkewX(0.f);
        p.setStrikeThruText(false);
    }

    private static int applyDim(int color) {
        int red = (0xFF & (color >> 16)) * 2 / 3;
        int green = (0xFF & (color >> 8)) * 2 / 3;
        int blue = (0xFF & color) * 2 / 3;
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    public float getFontWidth() { return mFontWidth; }
    public int getFontLineSpacing() { return mFontLineSpacing; }
}
