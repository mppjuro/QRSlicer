package org.example;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * Kontroler przyjmujący JSON { "pngBytes": [0..255,...] } i zwracający obiekty CompressedBitmap.
 */
@RestController
@RequestMapping("/process")
public class BinaryzacjaController {

    private final ImageProcessor imageProcessor = new ImageProcessor();

    /**
     * Odbiera JSON, konwertuje go na ImageRequest,
     * wywołuje imageProcessor, zwraca listę CompressedBitmap (Spring zserializuje do JSON).
     */
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public List<ImageProcessor.CompressedBitmap> process(@RequestBody ImageRequest req) throws IOException {
        // Walidacja
        if (req.pngBytes == null || req.pngBytes.length == 0) {
            throw new IllegalArgumentException("Brak pngBytes w żądaniu JSON!");
        }

        // Konwersja Integer[] -> byte[]
        byte[] imageBytes = new byte[req.pngBytes.length];
        for (int i = 0; i < req.pngBytes.length; i++) {
            imageBytes[i] = (byte)(req.pngBytes[i] & 0xFF);
        }

        // Wywołanie logiki z ImageProcessor
        // Metoda zwraca listę obiektów CompressedBitmap
        return imageProcessor.processImage(imageBytes);
    }
}
