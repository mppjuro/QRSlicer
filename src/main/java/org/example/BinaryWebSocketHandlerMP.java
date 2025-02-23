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
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        SessionState state = sessionStates.computeIfAbsent(session.getId(), k -> new SessionState());
        ByteBuffer buffer = message.getPayload();

        if (!state.dimensionsReceived) {
            // Pierwszy fragment zawiera wymiary
            state.width = buffer.getInt();
            state.height = buffer.getInt();
            state.dimensionsReceived = true;
            System.out.println("Otrzymano wymiary: " + state.width + "x" + state.height);
        } else {
            // Kolejne fragmenty z danymi RGB
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            state.imageBuffer.write(bytes);
        }
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
            System.out.println("Przetworzono");
        }
    }

    private void processCompleteImage(WebSocketSession session, SessionState state) throws IOException {
        // Konwersja bufora na obraz
        byte[] imageBytes = state.imageBuffer.toByteArray();
        BufferedImage receivedImage = new BufferedImage(state.width, state.height, BufferedImage.TYPE_INT_RGB);

        // Wypełnij obraz danymi RGB
        ByteBuffer byteBuffer = ByteBuffer.wrap(imageBytes);
        int[] pixels = new int[imageBytes.length / 4];
        byteBuffer.asIntBuffer().get(pixels);

        // Zamiana kanałów czerwonego i niebieskiego
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int red = (pixel >> 16) & 0xff;
            int green = (pixel >> 8) & 0xff;
            int blue = pixel & 0xff;
            // Zamiana: czerwony <-> niebieski
            pixels[i] = (blue << 16) | (green << 8) | red;
        }

        receivedImage.setRGB(0, 0, state.width, state.height, pixels, 0, state.width);

        // Zapis oryginalnego obrazu jako received.png
        ImageIO.write(receivedImage, "png", new File("received.png"));

        // Przetwarzanie obrazu -> uzyskanie listy skompresowanych bitmap
        List<ImageProcessor.CompressedBitmap> compressedBitmaps = imageProcessor.processImage(receivedImage);
        if (compressedBitmaps.isEmpty()) {
            System.err.println("Błąd: Przetwarzanie obrazu nie zwróciło wyników.");
            return;
        }

        int numImages = compressedBitmaps.size();
        int totalDataSize = 1 + numImages * 4; // Pierwszy element to liczba obrazów, reszta: width, height + dane

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
            // Zapis obrazu do odpowiedniego pliku (np. I.png, II.png, itd.)
            File outputFile = outputFiles.get(i);
            boolean saved = ImageIO.write(outputImage, "png", outputFile);
            if(saved) {
                System.out.println("Zapisano wykres do: " + outputFile.getName());
            } else {
                System.err.println("Nie udało się zapisać wykresu: " + outputFile.getName());
            }

            // Pakowanie danych obrazu do bufora
            compressedData[index++] = cb.smallPx;
            compressedData[index++] = cb.width;
            compressedData[index++] = cb.height;
            compressedData[index++] = cb.n;
            System.arraycopy(cb.data, 0, compressedData, index, cb.data.length);
            index += cb.data.length;
        }

        // Wysłanie skompresowanych danych do klienta
        ByteBuffer responseBuffer = ByteBuffer.allocate(compressedData.length * 4);
        IntBuffer intBuffer = responseBuffer.asIntBuffer();
        intBuffer.put(compressedData);
        session.sendMessage(new BinaryMessage(responseBuffer.array()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionStates.remove(session.getId());
    }

    private static class SessionState {
        int width;
        int height;
        ByteArrayOutputStream imageBuffer = new ByteArrayOutputStream();
        boolean dimensionsReceived = false;
    }
}