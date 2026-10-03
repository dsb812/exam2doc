package com.drok.exam2doc;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 极简 OOXML writer：文本段落 + 内嵌 PNG 图片，生成 .docx。 */
public class DocxWriter {

    public static byte[] write(List<OcrPipeline.Item> items) throws Exception {
        StringBuilder body = new StringBuilder();
        int imgN = 0;
        StringBuilder rels = new StringBuilder();

        for (OcrPipeline.Item it : items) {
            if (it.isImage) {
                imgN++;
                byte[] png = OcrPipeline.bitmapPng(it.image);
                int[] wh = pngSize(png);
                long[] emu = fitEmu(wh[0], wh[1]);
                rels.append("<Relationship Id=\"rIdImg").append(imgN)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image")
                        .append(imgN).append(".png\"/>");
                body.append(imageParagraph(imgN, emu[0], emu[1]));
            } else {
                body.append(textParagraph(it.text));
            }
        }

        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document"
                + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<w:body>" + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>"
                + "</w:body></w:document>";

        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";

        String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";

        String docRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + rels + "</Relationships>";

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bo);
        put(zip, "[Content_Types].xml", contentTypes.getBytes(StandardCharsets.UTF_8));
        put(zip, "_rels/.rels", rootRels.getBytes(StandardCharsets.UTF_8));
        put(zip, "word/document.xml", document.getBytes(StandardCharsets.UTF_8));
        put(zip, "word/_rels/document.xml.rels", docRels.getBytes(StandardCharsets.UTF_8));
        int i = 0;
        for (OcrPipeline.Item it : items) {
            if (it.isImage) {
                i++;
                put(zip, "word/media/image" + i + ".png", OcrPipeline.bitmapPng(it.image));
            }
        }
        zip.close();
        return bo.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static String textParagraph(String text) {
        return "<w:p><w:r><w:rPr>"
                + "<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:eastAsia=\"宋体\"/>"
                + "<w:sz w:val=\"21\"/>"
                + "</w:rPr><w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    private static String imageParagraph(int n, long cx, long cy) {
        return "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>"
                + "<wp:inline xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
                + " distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                + "<wp:docPr id=\"" + n + "\" name=\"img" + n + "\"/>"
                + "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                + "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:nvPicPr><pic:cNvPr id=\"" + n + "\" name=\"img" + n + "\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"rIdImg" + n + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
    }

    private static long[] fitEmu(int wPx, int hPx) {
        long maxW = 5570400L; // 5.8 inch
        long cx = wPx * 9525L, cy = hPx * 9525L;
        if (cx > maxW) {
            cy = cy * maxW / cx;
            cx = maxW;
        }
        return new long[]{cx, cy};
    }

    private static int[] pngSize(byte[] png) {
        // PNG 宽高位于 IHDR：偏移 16-23
        int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        return new int[]{w, h};
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
