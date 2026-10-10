package ru.cineflow.notification;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.util.Map;

final class QrCodes {
    private QrCodes() {}

    static byte[] png(String ticketId) {
        try {
            var m = new QRCodeWriter().encode("CINEFLOW:" + ticketId, BarcodeFormat.QR_CODE, 260, 260,
                    Map.of(EncodeHintType.MARGIN, 2));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(m, "PNG", out, new MatrixToImageConfig(0xFF2A1024, 0xFFFFFFFF));
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
