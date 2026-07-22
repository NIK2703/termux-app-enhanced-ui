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

    private final Rect mTempRect = new Rect();

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

    private static int classify(int codePoint) {
        if (isEmojiPresentation(codePoint)) return CLS_FIT;
        if ((codePoint >= 0x2500 && codePoint <= 0x257F) ||
            (codePoint >= 0x2580 && codePoint <= 0x259F) ||
            (codePoint >= 0x25A0 && codePoint <= 0x25FF) ||
            (codePoint >= 0x2190 && codePoint <= 0x21FF) ||
            (codePoint >= 0x2200 && codePoint <= 0x22FF)) {
            return CLS_NATURAL;
        }
        return CLS_NORMAL;
    }

    private static boolean isEmojiPresentation(int cp) {
        if (cp >= 0x1F300 && cp <= 0x1F5FF) return true;
        if (cp >= 0x1F600 && cp <= 0x1F64F) return true;
        if (cp >= 0x1F680 && cp <= 0x1F6FF) return true;
        if (cp >= 0x1F900 && cp <= 0x1F9FF) return true;
        if (cp >= 0x1FA00 && cp <= 0x1FAFF) return true;
        if (cp >= 0x2600 && cp <= 0x26FF) return true;
        if (cp >= 0x2700 && cp <= 0x27BF) return true;
        if (cp >= 0x1F1E6 && cp <= 0x1F1FF) return true;
        if (cp >= 0x1F000 && cp <= 0x1F0FF) return true;
        if (cp >= 0xFE00 && cp <= 0xFE0F) return true;
        if (cp == 0x200D) return true;
        if (cp == 0x20E3) return true;
        if (cp >= 0x1F3FB && cp <= 0x1F3FF) return true;
        if (cp >= 0xE0020 && cp <= 0xE007F) return true;
        if (cp >= 0x2B50 && cp <= 0x2B55) return true;
        if (cp >= 0x231A && cp <= 0x231B) return true;
        if (cp >= 0x23E9 && cp <= 0x23F3) return true;
        if (cp >= 0x2934 && cp <= 0x2935) return true;
        if (cp >= 0x2B05 && cp <= 0x2B07) return true;
        if (cp >= 0x2B1B && cp <= 0x2B1C) return true;
        if (cp == 0x3030) return true;
        if (cp == 0x303D) return true;
        if (cp == 0x3297) return true;
        if (cp == 0x3299) return true;
        if (cp == 0x00A9 || cp == 0x00AE) return true;
        if (cp == 0x2122) return true;
        if (cp >= 0x2648 && cp <= 0x2653) return true;
        if (cp == 0x2693) return true;
        if (cp == 0x26A1) return true;
        if (cp >= 0x26AA && cp <= 0x26AB) return true;
        if (cp >= 0x26BD && cp <= 0x26BE) return true;
        if (cp >= 0x26C4 && cp <= 0x26C5) return true;
        if (cp == 0x26D4) return true;
        if (cp == 0x26EA) return true;
        if (cp >= 0x26F2 && cp <= 0x26F3) return true;
        if (cp == 0x26F5) return true;
        if (cp == 0x26FA) return true;
        if (cp == 0x26FD) return true;
        if (cp == 0x2702) return true;
        if (cp == 0x2705) return true;
        if (cp >= 0x2708 && cp <= 0x270D) return true;
        if (cp == 0x270F) return true;
        if (cp == 0x2712) return true;
        if (cp == 0x2714) return true;
        if (cp == 0x2716) return true;
        if (cp == 0x271D) return true;
        if (cp == 0x2721) return true;
        if (cp == 0x2728) return true;
        if (cp >= 0x2733 && cp <= 0x2734) return true;
        if (cp == 0x2744) return true;
        if (cp == 0x2747) return true;
        if (cp == 0x274C) return true;
        if (cp == 0x274E) return true;
        if (cp >= 0x2753 && cp <= 0x2755) return true;
        if (cp == 0x2757) return true;
        if (cp >= 0x2763 && cp <= 0x2764) return true;
        if (cp >= 0x2795 && cp <= 0x2797) return true;
        if (cp == 0x27A1) return true;
        if (cp == 0x27B0) return true;
        if (cp == 0x27BF) return true;
        return false;
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

    private GlyphMetric measureCluster(char[] line, int start, int len) {
        String key = new String(line, start, len);
        GlyphMetric cached = mSequenceMetrics.get(key);
        if (cached != null) return cached;

        float advance = measureWithPaint(mEmojiPaint, line, start, len);
        mEmojiPaint.getTextBounds(line, start, len, mTempRect);
        GlyphMetric metric = new GlyphMetric(advance, 1, mTempRect);
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

                final int cls;
                if (isMultiCpCluster) {
                    cls = CLS_FIT;
                } else {
                    cls = classify(codePoint);
                }

                final float measuredWidth;
                if (cls == CLS_FIT) {
                    GlyphMetric gm = measureCluster(line, currentCharIndex, clusterLen);
                    measuredWidth = gm.advance;
                } else if (codePoint < asciiMeasures.length && clusterLen == 1) {
                    measuredWidth = asciiMeasures[codePoint];
                } else {
                    measuredWidth = measureCodePointText(codePoint);
                }

                final boolean fontWidthMismatch =
                        Math.abs(measuredWidth / mFontWidth - codePointWcWidth) > 0.01;

                final boolean forceBreak = (cls != CLS_NORMAL);

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

                if (cls == CLS_FIT) {
                    drawEmojiCell(canvas, line, currentCharIndex, clusterLen, palette,
                            heightOffset, column, codePointWcWidth, style,
                            insideCursor, insideSelection, cursorShape, reverseVideo, mEmulator);
                    lastRunStartColumn = column + codePointWcWidth;
                    lastRunStartIndex = currentCharIndex + clusterLen;
                    measuredWidthForRun = 0.f;
                    lastRunStyle = 0;
                    lastRunInsideCursor = false;
                    lastRunInsideSelection = false;
                    lastRunFontWidthMismatch = false;
                } else if (cls == CLS_NATURAL) {
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

    private void drawEmojiCell(Canvas canvas, char[] line, int start, int len,
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
            int tmp = foreColor; foreColor = backColor; backColor = tmp;
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
            float cLeft = cellLeft, cRight = cellRight, cTop = cellTop, cBottom = cellBottom;
            if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                cTop = cellBottom - (cellBottom - cellTop) / 4.f;
            } else if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
                cRight = cLeft + (cRight - cLeft) / 4.f;
            }
            canvas.drawRect(cLeft, cTop, cRight, cBottom, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0) return;
        if (dim) foreColor = applyDim(foreColor);

        GlyphMetric gm = measureCluster(line, start, len);
        float inkW = gm.inkWidth();
        float inkH = gm.inkHeight();

        mEmojiPaint.setFakeBoldText(false);
        mEmojiPaint.setUnderlineText(underline);
        mEmojiPaint.setTextSkewX(italic ? -0.35f : 0.f);
        mEmojiPaint.setStrikeThruText(strikeThrough);
        mEmojiPaint.setColor(foreColor);

        if (inkW <= 0 || inkH <= 0) {
            float baselineY = y - mFontLineSpacingAndAscent;
            canvas.drawTextRun(line, start, len, start, len, cellLeft, baselineY, false, mEmojiPaint);
            return;
        }

        float cellW = cellCols * mFontWidth;
        float cellH = mFontLineSpacing;
        float cellCenterX = cellLeft + cellW / 2.f;
        float cellCenterY = cellTop + cellH / 2.f;

        float s = Math.min(cellW / inkW, cellH / inkH);
        if (s > 1.f) s = 1.f;

        float inkCX = gm.inkCenterX();
        float inkCY = gm.inkCenterY();

        canvas.save();
        canvas.translate(cellCenterX, cellCenterY);
        canvas.scale(s, s);
        canvas.drawTextRun(line, start, len, start, len, -inkCX, -inkCY, false, mEmojiPaint);
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
            int tmp = foreColor; foreColor = backColor; backColor = tmp;
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
            float cLeft = cellLeft, cRight = cellRight, cTop = cellTop, cBottom = cellBottom;
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
            float cLeft = cellLeft, cRight = cellRight, cTop = cellTop, cBottom = cellBottom;
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
