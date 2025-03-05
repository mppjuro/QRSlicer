package org.example;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class BinaryWebSocketHandlerMP extends BinaryWebSocketHandler {
    private final ConcurrentHashMap<String, SessionState> sessionStates = new ConcurrentHashMap<>();
    private final ImageProcessor imageProcessor;

    private static final List<File> outputFiles = Arrays.asList(
            new File("I.png"), new File("II.png"), new File("III.png"),
            new File("aVR.png"), new File("aVL.png"), new File("aVF.png"),
            new File("V1.png"), new File("V2.png"), new File("V3.png"),
            new File("V4.png"), new File("V5.png"), new File("V6.png")
    );

    public BinaryWebSocketHandlerMP(ImageProcessor imageProcessor) {
        this.imageProcessor = imageProcessor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setBinaryMessageSizeLimit(20 * 1024 * 1024);
        session.setTextMessageSizeLimit(20 * 1024 * 1024);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        SessionState state = sessionStates.computeIfAbsent(session.getId(), k -> new SessionState());
        ByteBuffer buffer = message.getPayload();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        state.imageBuffer.write(bytes);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        if ("KONIEC".equals(payload)) {
            System.out.println("Otrzymano sygnał końcowy. Przetwarzanie obrazu...");
            try {
                SessionState state = sessionStates.remove(session.getId());
                if (state != null) {
                    processCompleteImage(session, state);
                }
            } catch (IOException e) {
                System.err.println("Błąd przetwarzania obrazu: " + e.getMessage());
            }
            System.out.println("Przetworzono.");
        }
    }

    private void processCompleteImage(WebSocketSession session, SessionState state) throws IOException {
        byte[] imageBytes = state.imageBuffer.toByteArray();
        BufferedImage receivedImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (receivedImage == null) {
            throw new IOException("Nie udało się odczytać obrazu PNG.");
        }
        int width = receivedImage.getWidth();
        int height = receivedImage.getHeight();

        // Zapis oryginalnego obrazu jako received.png (dla debugowania)
        ImageIO.write(receivedImage, "png", new File("received.png"));

        // Przetwarzamy obraz – otrzymujemy listę 12 skompresowanych bitmap (bez zmian)
        java.util.List<ImageProcessor.CompressedBitmap> compressedBitmaps = imageProcessor.processImage(receivedImage);
        if (compressedBitmaps.isEmpty()) {
            System.err.println("Błąd: Przetwarzanie obrazu nie zwróciło wyników.");
            return;
        }

        int numImages = compressedBitmaps.size();
        // Całkowity rozmiar danych: 1 int (liczba obrazów) + dla każdego obrazu 4 inty + długość tablicy danych
        int totalDataSize = 1 + numImages * 4;
        for (ImageProcessor.CompressedBitmap cb : compressedBitmaps) {
            totalDataSize += cb.data.length;
        }

        int[] compressedData = new int[totalDataSize];
        compressedData[0] = numImages;

        int index = 1;
        for (int i = 0; i < numImages; i++) {
            ImageProcessor.CompressedBitmap cb = compressedBitmaps.get(i);

            // Odtworzenie i zapis obrazu w formie PNG
            BufferedImage outputImage = new BufferedImage(cb.width, cb.height, BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < cb.height; y++) {
                for (int x = 0; x < cb.width; x++) {
                    int bitIndex = y * cb.width + x;
                    int value = (cb.data[bitIndex / 32] >> (bitIndex % 32)) & 1;
                    outputImage.setRGB(x, y, value == 1 ? 0x000000 : 0xFFFFFF);
                }
            }

            File outputFile = outputFiles.get(i);
            boolean saved = ImageIO.write(outputImage, "png", outputFile);
            if (saved) {
                System.out.println("Zapisano wykres do: " + outputFile.getName());
            } else {
                System.err.println("Nie udało się zapisać wykresu: " + outputFile.getName());
            }

            compressedData[index++] = cb.smallPx;
            compressedData[index++] = cb.width;
            compressedData[index++] = cb.height;
            compressedData[index++] = cb.n;
            System.arraycopy(cb.data, 0, compressedData, index, cb.data.length);
            index += cb.data.length;
        }

        ByteBuffer responseBuffer = ByteBuffer.allocate(compressedData.length * 4); // int = 4 bajty
        IntBuffer intBuffer = responseBuffer.asIntBuffer();
        intBuffer.put(compressedData);
        session.sendMessage(new BinaryMessage(responseBuffer.array()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionStates.remove(session.getId());
    }

    private static class SessionState {
        ByteArrayOutputStream imageBuffer = new ByteArrayOutputStream();
    }
}
