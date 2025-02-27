package org.example;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ImageProcessor {

    // Klasa sub–obrazu
    public static class CompressedBitmap {
        public int smallPx;
        public int width;
        public int height;
        public int n;
        public int[] data;
    }

    /**
     * Główna metoda przetwarzająca:
     *  - Wczytuje bajty PNG -> BufferedImage
     *  - Wykryqwa ilość px na kratkę małą
     *  - Binaryzacja -> boolean[][] (+ warunek r<150... => false)
     *  - Usuwanie samotnych pikseli
     *  - Odcinanie lewego marginesu
     *  - Szukanie 7 bloków białych w poziomie, żeby pociąć na 6 linii
     *  - Pionowa linia -> środek (width/2)
     *  - Cięcie na 8×2 segmentów (6x2 to EKG) i górny oraz dolny margines
     *  - Zwraca listę obiektów CompressedBitmap
     */
    public List<CompressedBitmap> processImage(BufferedImage input) throws IOException {
        if (input == null) {
            throw new IOException("Nie udało się wczytać obrazu.");
        }

        int origWidth = input.getWidth();
        int origHeight = input.getHeight();

        // 1. Wykrywanie ilości px na kratkę małą (1/5 px na kratkę dużą)
        List<Integer> horizontalRedLines = new ArrayList<>();
        List<Integer> verticalRedLines = new ArrayList<>();
        int redThreshold = 200;   // Początkowy próg dla kanału R
        int greenBlueThreshold = 100; // Maksymalna wartość dla kanałów G i B
        int requiredPercentage = 70; // Wymagany % czerwonych pikseli
        int attempts = 0;
        boolean vlinesDetected = false;
        boolean hlinesDetected = false;

        do {
            horizontalRedLines.clear();
            verticalRedLines.clear();

            // Wykrywanie poziomych linii
            for (int y = 0; y < origHeight; y++) {
                int redPixels = 0;
                for (int x = 0; x < origWidth; x++) {
                    Color c = new Color(input.getRGB(x, y));
                    if (c.getRed() >= redThreshold && c.getGreen() <= greenBlueThreshold && c.getBlue() <= greenBlueThreshold) {
                        redPixels++;
                    }
                }
                double percentage = (redPixels * 100.0) / origWidth;
                if (percentage >= requiredPercentage) {
                    horizontalRedLines.add(y);
                }
            }

            // Wykrywanie pionowych linii
            for (int x = 0; x < origWidth; x++) {
                int redPixels = 0;
                for (int y = 0; y < origHeight; y++) {
                    Color c = new Color(input.getRGB(x, y));
                    if (c.getRed() >= redThreshold && c.getGreen() <= greenBlueThreshold && c.getBlue() <= greenBlueThreshold) {
                        redPixels++;
                    }
                }
                double percentage = (redPixels * 100.0) / origHeight;
                if (percentage >= requiredPercentage) {
                    verticalRedLines.add(x);
                }
            }

            // Dostosowanie progu czerwieni, aby liczba linii mieściła się w zakresie
            if (verticalRedLines.size() < 80) {
                redThreshold = Math.max(0, redThreshold - 5);
            } else if (verticalRedLines.size() > 130) {
                redThreshold = Math.min(255, redThreshold + 5);
            } else {
                vlinesDetected = true;
            }

            if (horizontalRedLines.size() < 30) {
                redThreshold = Math.max(0, redThreshold - 5);
            } else if (horizontalRedLines.size() > 60) {
                redThreshold = Math.min(255, redThreshold + 5);
            } else {
                hlinesDetected = true;
            }

            attempts++;
        } while ((!vlinesDetected || !hlinesDetected) && attempts < 20 && !vlinesDetected);

        // Zaznacz linie na obrazie i zapisz
        BufferedImage linesImage = copyBufferedImage(input);
        Graphics2D g = linesImage.createGraphics();
        g.setColor(Color.GREEN);
        g.setStroke(new BasicStroke(2f));

        for (Integer y : horizontalRedLines) {
            g.drawLine(0, y, linesImage.getWidth() - 1, y);
        }
        for (Integer x : verticalRedLines) {
            g.drawLine(x, 0, x, linesImage.getHeight() - 1);
        }
        g.dispose();
        ImageIO.write(linesImage, "png", new File("received-lines.png"));

        int minHorizontalGap = calculateMinGap(horizontalRedLines);
        int minVerticalGap = calculateMinGap(verticalRedLines);
        if (minHorizontalGap <= 0) minHorizontalGap = 1000;
        if (minVerticalGap <= 0) minVerticalGap = 1000;
        int bigPx = Math.min(minHorizontalGap, minVerticalGap);
        int smallPx = 1000000 * bigPx / 5; // razy 1M, żeby wysłać jako int
        System.out.println("Px na kratkę: " + (double)smallPx/1000000.0);

        // 2. Binaryzacja + usuwanie samotnych pikseli
        boolean[][] matrix = new boolean[origHeight][origWidth];
        for (int y = 0; y < origHeight; y++) {
            for (int x = 0; x < origWidth; x++) {
                Color c = new Color(input.getRGB(x, y), true);
                int r = c.getRed();
                int green = c.getGreen();
                int b = c.getBlue();

                // Binaryzacja
                boolean val = (r < 200 && green < 200);
                if (r < 150 && green < 150 && b < 120) {
                    val = false;
                }
                matrix[y][x] = val;
            }
        }
        matrix = removeLonelyPixels(matrix);

        // 3. Usuwanie lewego pustego marginesu
        int leftMargin = findLeftMargin(matrix);
        if (leftMargin > 0) {
            matrix = cutLeft(matrix, leftMargin);
        }

        int width = matrix[0].length;
        int height = matrix.length;

        // 4. Szukamy 7 linii poziomych
        List<Integer> hLines = null;
        boolean fallback = false;
        try {
            hLines = find7HorizontalLines(matrix);
            if (hLines.size() != 7) {
                throw new RuntimeException("Nie znaleziono 7 linii (znaleziono=" + hLines.size() + ")");
            }
        } catch (Exception e) {
            System.err.println("Nieudana detekcja 7 linii poziomych: " + e.getMessage());
            fallback = true;
        }

        // 5. Pionowa linia w samym środku
        int vLine = width / 2;

        // 6. Tniemy na 8×2
        List<CompressedBitmap> resultList;
        if (!fallback) {
            resultList = cutIntoSegments(matrix, hLines, vLine, smallPx);
        } else {
            resultList = cutEqually(matrix, smallPx);
        }

        // 7. Rysowanie i zapis debug_output.png
        BufferedImage debugImg = copyBufferedImage(input);
        debugImg = debugImg.getSubimage(leftMargin, 0, width, height);
        drawDebugLines(debugImg, hLines, vLine, fallback);
        ImageIO.write(debugImg, "png", new File("received-cut.png"));

        return resultList;
    }

    private boolean[][] removeLonelyPixels(boolean[][] matrix) {
        int h = matrix.length, w = matrix[0].length;
        boolean[][] result = new boolean[h][w];
        for (int i = 0; i < h; i++) {
            System.arraycopy(matrix[i], 0, result[i], 0, w);
        }

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                if (matrix[y][x]) {
                    boolean lonely = true;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dy == 0 && dx == 0) continue;
                            if (matrix[y + dy][x + dx]) {
                                lonely = false;
                                break;
                            }
                        }
                        if (!lonely) break;
                    }
                    if (lonely) {
                        result[y][x] = false;
                    }
                }
            }
        }
        return result;
    }

    private int findLeftMargin(boolean[][] matrix) {
        int h = matrix.length;
        int w = matrix[0].length;
        int threshold = 10;
        for (int x = 0; x < w; x++) {
            int blackCount = 0;
            for (int y = 0; y < h; y++) {
                if (matrix[y][x]) blackCount++;
            }
            if (blackCount >= threshold) {
                return x;
            }
        }
        return 0;
    }

    private boolean[][] cutLeft(boolean[][] matrix, int left) {
        int h=matrix.length, w=matrix[0].length;
        int newW = w-left;
        if(newW<=0) return matrix;

        boolean[][] result = new boolean[h][newW];
        for(int y=0;y<h;y++){
            System.arraycopy(matrix[y], left, result[y], 0, newW);
        }
        return result;
    }

    // Znajdowanie 7 linii w poziomie - cięcie na 8 fragmentów (w tym 2 to marginesy)
    private List<Integer> find7HorizontalLines(boolean[][] matrix) {
        int h = matrix.length;
        int w = matrix[0].length;
        double rowThreshold = w * 0.01;
        double minimumPer = 0.01;
        List<WhiteBlock> blocks = null;

        // Zwiększamy minimumPer co 0.01, aż dokładnie 7 bloków zostanie wykrytych
        while (minimumPer <= 1.0) {
            double minBlockHeight = minimumPer * h;
            boolean[] isWhite = new boolean[h];
            for (int y = 0; y < h; y++) {
                int blackCount = 0;
                for (int x = 0; x < w; x++) {
                    if (matrix[y][x]) {
                        blackCount++;
                    }
                }
                isWhite[y] = (blackCount < rowThreshold);
            }

            blocks = new ArrayList<>();
            int idx = 0;
            while (idx < h) {
                if (!isWhite[idx]) {
                    idx++;
                    continue;
                }
                int start = idx;
                while (idx < h && isWhite[idx]) {
                    idx++;
                }
                int end = idx - 1;
                int blockHeight = end - start + 1;
                if (blockHeight >= minBlockHeight) {
                    blocks.add(new WhiteBlock(start, end));
                }
            }
            System.out.println("Znaleziono: " + blocks.size() + "poziomych linii");
            if (blocks.size() == 7) {
                break;
            }
            if (blocks.size() > 7) {
                minimumPer *= 1.1;
                rowThreshold *= 0.9;
            }
            else {
                minimumPer *= 0.9;
                rowThreshold *= 1.1;
            }
        }

        if (blocks == null || blocks.size() != 7) {
            throw new RuntimeException("Nie udało się znaleźć dokładnie 7 linii przy określonym minimumPer");
        }

        List<Integer> lines = new ArrayList<>(7);
        List<WhiteBlock> used = blocks.subList(0, 7);
        for (int i = 0; i < 7; i++) {
            WhiteBlock wb = used.get(i);
            int blockH = wb.end - wb.start + 1;
            double ratio = 0.5; // domyślnie środek bloku
            if (i == 0) {
                ratio = 0.7; // dla pierwszego bloku – cięcie bliżej dolnej krawędzi
            } else if (i == 6) {
                ratio = 0.3; // dla ostatniego bloku – cięcie bliżej górnej krawędzi
            }
            int cutLine = (int) (wb.start + ratio * blockH);
            lines.add(cutLine);
        }
        lines.sort(Integer::compareTo);
        return lines;
    }

    private static class WhiteBlock {
        int start, end;
        WhiteBlock(int s, int e) { start = s; end = e; }
    }

    // Cięcie 8x2
    private List<CompressedBitmap> cutIntoSegments(boolean[][] matrix, List<Integer> hLines, int vLine, int smallPx) {
        int h = matrix.length, w = matrix[0].length;
        hLines.sort(Integer::compareTo);
        List<Integer> finalY = new ArrayList<>();
        finalY.add(0);
        finalY.addAll(hLines);
        finalY.add(h);

        int halfW = w / 2;
        List<boolean[][]> leftSubMatrices = new ArrayList<>();
        List<boolean[][]> rightSubMatrices = new ArrayList<>();

        for (int i = 1; i < finalY.size() - 2; i++) {
            int y1 = finalY.get(i);
            int y2 = finalY.get(i + 1);
            int segH = y2 - y1;

            boolean[][] leftSub = new boolean[segH][halfW];
            boolean[][] rightSub = new boolean[segH][w - halfW];

            for (int yy = 0; yy < segH; yy++) {
                System.arraycopy(matrix[y1 + yy], 0, leftSub[yy], 0, halfW);
                System.arraycopy(matrix[y1 + yy], halfW, rightSub[yy], 0, w - halfW);
            }
            leftSubMatrices.add(leftSub);
            rightSubMatrices.add(rightSub);
        }

        // Najpierw lewa kolumna (I, II, III, aVR, aVL, aVF), potem prawa (V1, V2, V3, V4, V5, V6)
        List<boolean[][]> subMatrices = new ArrayList<>();
        subMatrices.addAll(leftSubMatrices);
        subMatrices.addAll(rightSubMatrices);

        // Wyznaczamy minimalne wymiary spośród 12 głównych wykresów EKG
        int minH = subMatrices.stream().mapToInt(m -> m.length).min().orElse(0);
        int minW = subMatrices.stream().mapToInt(m -> m[0].length).min().orElse(0);

        // Przycinamy każdy segment do (minH x minW), a następnie usuwamy 5% z lewej i prawej
        List<CompressedBitmap> list = new ArrayList<>();
        for (boolean[][] sub : subMatrices) {
            boolean[][] trimmed = trimToSize(sub, minH, minW);
            trimmed = trimLeftRight(trimmed, 0.05); // usuń 5% z obu stron
            int[] compressed = compressBooleanMatrix(trimmed);
            CompressedBitmap cb = new CompressedBitmap();
            cb.smallPx = smallPx;
            cb.width = trimmed[0].length;
            cb.height = trimmed.length;
            cb.n = compressed.length;
            cb.data = compressed;
            list.add(cb);
        }
        return list;
    }

    private List<CompressedBitmap> cutEqually(boolean[][] matrix, int smallPx) {
        int h = matrix.length, w = matrix[0].length;
        int rowH = h / 8;
        int colW = w / 2;
        List<boolean[][]> leftSubMatrices = new ArrayList<>();
        List<boolean[][]> rightSubMatrices = new ArrayList<>();

        for (int row = 1; row < 7; row++) {
            int y1 = row * rowH;
            int y2 = (row + 1) * rowH;
            int segH = y2 - y1;

            boolean[][] leftSub = new boolean[segH][colW];
            boolean[][] rightSub = new boolean[segH][w - colW];

            for (int yy = 0; yy < segH; yy++) {
                System.arraycopy(matrix[y1 + yy], 0, leftSub[yy], 0, colW);
                System.arraycopy(matrix[y1 + yy], colW, rightSub[yy], 0, w - colW);
            }
            leftSubMatrices.add(leftSub);
            rightSubMatrices.add(rightSub);
        }

        List<boolean[][]> subMatrices = new ArrayList<>();
        subMatrices.addAll(leftSubMatrices);
        subMatrices.addAll(rightSubMatrices);

        int minH = subMatrices.stream().mapToInt(m -> m.length).min().orElse(0);
        int minW = subMatrices.stream().mapToInt(m -> m[0].length).min().orElse(0);

        List<CompressedBitmap> list = new ArrayList<>();
        for (boolean[][] sub : subMatrices) {
            boolean[][] trimmed = trimToSize(sub, minH, minW);
            trimmed = trimLeftRight(trimmed, 0.05); // usuń 5% z lewej i prawej
            int[] compressed = compressBooleanMatrix(trimmed);
            CompressedBitmap cb = new CompressedBitmap();
            cb.smallPx = smallPx;
            cb.width = trimmed[0].length;
            cb.height = trimmed.length;
            cb.n = compressed.length;
            cb.data = compressed;
            list.add(cb);
        }
        return list;
    }

    private int[] compressBooleanMatrix(boolean[][] subMatrix) {
        int hh=subMatrix.length;
        int ww=subMatrix[0].length;
        int total=hh*ww;
        int nInts=(total+31)/32;
        int[] result=new int[nInts];

        int bitIndex=0, intIndex=0, current=0;
        for(int y=0;y<hh;y++){
            for(int x=0;x<ww;x++){
                if(subMatrix[y][x]){
                    current |= (1 << bitIndex);
                }
                bitIndex++;
                if(bitIndex==32){
                    result[intIndex]=current;
                    intIndex++;
                    bitIndex=0;
                    current=0;
                }
            }
        }
        if(bitIndex>0){
            result[intIndex]=current;
        }
        return result;
    }

    private void drawDebugLines(BufferedImage img,
                                List<Integer> hLines,
                                int vLine,
                                boolean fallback) throws IOException {
        Graphics2D g2d= img.createGraphics();
        g2d.setColor(Color.RED);
        g2d.setStroke(new BasicStroke(2f));

        int w=img.getWidth(), h=img.getHeight();

        if(fallback){
            int rowH = h/8;
            for(int i=1;i<8;i++){
                int Y=i*rowH;
                g2d.drawLine(0,Y, w-1,Y);
            }
            int midX=w/2;
            g2d.drawLine(midX,0, midX,h-1);
        } else {
            if(hLines!=null){
                for(int yVal : hLines){
                    g2d.drawLine(0,yVal, w-1,yVal);
                }
            }
            int midX=w/2;
            g2d.drawLine(midX,0,midX,h-1);
        }
        g2d.dispose();
    }

    private BufferedImage copyBufferedImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        Graphics g=copy.getGraphics();
        g.drawImage(src,0,0,null);
        g.dispose();
        return copy;
    }

    private boolean[][] trimToSize(boolean[][] matrix, int targetH, int targetW) {
        int h = matrix.length;
        int w = matrix[0].length;
        boolean[][] result = new boolean[targetH][targetW];
        int startY = Math.max(0, (h - targetH) / 2);
        int startX = Math.max(0, (w - targetW) / 2);
        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                result[y][x] = matrix[startY + y][startX + x];
            }
        }
        return result;
    }

    private boolean[][] trimLeftRight(boolean[][] matrix, double ratio) {
        int w = matrix[0].length;
        int cut = (int) (w * ratio);
        int newW = w - 2 * cut;
        if (newW <= 0) return matrix;
        boolean[][] result = new boolean[matrix.length][newW];
        for (int y = 0; y < matrix.length; y++) {
            System.arraycopy(matrix[y], cut, result[y], 0, newW);
        }
        return result;
    }

    private int calculateMinGap(List<Integer> lines) {
        if (lines.size() < 2) return 0;
        List<Integer> sorted = new ArrayList<>(lines);
        Collections.sort(sorted);
        int minGap = Integer.MAX_VALUE;
        for (int i = 1; i < sorted.size(); i++) {
            int gap = sorted.get(i) - sorted.get(i - 1);
            if (gap > 0 && gap < minGap) minGap = gap;
        }
        return minGap == Integer.MAX_VALUE ? 0 : minGap;
    }
}