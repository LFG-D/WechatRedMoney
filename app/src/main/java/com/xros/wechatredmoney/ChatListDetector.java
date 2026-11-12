package com.xros.wechatredmoney;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 微信会话列表截图文字识别：定位含“微信红包”的会话行。必须在后台线程调用 analyze。 */
public final class ChatListDetector {

    public static final class Row {
        public Rect line;
        public String text;
        public String title;
        public boolean unread;

        @Override
        public String toString() {
            return "Row{title=" + title + ", text=" + text + ", unread=" + unread + ", line=" + line.toShortString() + "}";
        }
    }

    public static final class Result {
        public boolean chatList;
        public int width;
        public int height;
        public final List<Row> rows = new ArrayList<>();
    }

    private final TextRecognizer recognizer =
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    public Result analyze(Bitmap bmp) throws Exception {
        Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 3, TimeUnit.SECONDS);
        int w = bmp.getWidth(), h = bmp.getHeight();
        Result result = new Result();
        result.width = w;
        result.height = h;

        List<Text.Line> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (line.getBoundingBox() != null) lines.add(line);
            }
        }

        boolean contacts = false, discover = false;
        for (Text.Line line : lines) {
            Rect b = line.getBoundingBox();
            if (b.centerY() < h * 0.85f) continue;
            if (line.getText().contains("通讯录")) contacts = true;
            if (line.getText().contains("发现")) discover = true;
        }
        result.chatList = contacts && discover;
        if (!result.chatList) return result;

        for (Text.Line line : lines) {
            Rect b = line.getBoundingBox();
            if (!line.getText().contains(WeChatUi.PACKET_TEXT)) continue;
            if (b.centerY() < h * 0.08f || b.centerY() > h * 0.88f) continue;
            Row row = new Row();
            row.line = b;
            row.text = line.getText();
            row.title = findTitle(lines, b, w);
            int top = Math.max(0, b.top - b.height() * 3);
            row.unread = hasRedBadge(bmp, 0, top, (int) (w * 0.28f), Math.min(h, b.bottom));
            result.rows.add(row);
        }
        Collections.sort(result.rows, (a, c) -> a.line.top - c.line.top);
        return result;
    }

    // 会话名在预览文字正上方
    private static String findTitle(List<Text.Line> lines, Rect preview, int w) {
        String best = "";
        int bestGap = Integer.MAX_VALUE;
        for (Text.Line l : lines) {
            Rect b = l.getBoundingBox();
            int gap = preview.top - b.bottom;
            if (gap < -5 || gap > preview.height() * 2.5f) continue;
            if (Math.abs(b.left - preview.left) > w * 0.1f) continue;
            if (gap < bestGap) {
                bestGap = gap;
                best = l.getText();
            }
        }
        return best;
    }

    // 未读角标：微信红色约 #FA5151
    private static boolean hasRedBadge(Bitmap bmp, int left, int top, int right, int bottom) {
        int count = 0;
        for (int y = top; y < bottom; y += 2) {
            for (int x = left; x < right; x += 2) {
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
                if (r >= 220 && g <= 120 && bl <= 120 && ++count >= 15) return true;
            }
        }
        return false;
    }

    public void close() {
        recognizer.close();
    }
}
