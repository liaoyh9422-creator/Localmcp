package com.apkstoapk.app.ui.editor;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Basic AndroidManifest.xml validation for editor save path. */
public final class ManifestXmlValidator {
    private ManifestXmlValidator() {}

    public static final class Result {
        public final boolean ok;
        public final String message;
        public final int line;
        public final int column;

        public Result(boolean ok, String message, int line, int column) {
            this.ok = ok;
            this.message = message;
            this.line = line;
            this.column = column;
        }
    }

    public static Result validate(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            return new Result(false, "内容为空", 1, 1);
        }
        try {
            XmlPullParserFactory f = XmlPullParserFactory.newInstance();
            f.setNamespaceAware(true);
            XmlPullParser p = f.newPullParser();
            p.setInput(new StringReader(xml));
            boolean foundManifest = false;
            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if ("manifest".equals(p.getName())) {
                        foundManifest = true;
                    }
                }
                event = p.next();
            }
            if (!foundManifest) {
                return new Result(false, "未找到 <manifest> 根标签", 1, 1);
            }
            return new Result(true, "XML 校验通过", 0, 0);
        } catch (Exception e) {
            int line = 1;
            int col = 1;
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            try {
                Matcher mLine = Pattern.compile("(?i)line(?:Number)?\\s*[=:]\\s*(\\d+)").matcher(msg);
                if (mLine.find()) line = Integer.parseInt(mLine.group(1));
                Matcher mCol = Pattern.compile("(?i)column(?:Number)?\\s*[=:]\\s*(\\d+)").matcher(msg);
                if (mCol.find()) col = Integer.parseInt(mCol.group(1));
            } catch (Exception ignored) {
            }
            return new Result(false, msg, line, col);
        }
    }
}
