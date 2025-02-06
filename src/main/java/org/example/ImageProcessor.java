package org.example;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class ImageProcessor {

    /**
     * Główna metoda przetwarzająca:
     *  - Wczytuje bajty PNG -> BufferedImage
     *  - Binaryzacja -> boolean[][] (+ warunek r<150... => false)
     *  - Usuwanie samotnych pikseli
     *  - Odcinanie lewego marginesu
     *  - Szukanie 7 bloków białych (>=5% wysokości), pierwszy tniemy w 70%, ostatni w 30%, środkowe w 50%
     *  - Pionowa linia -> środek (width/2)
     *  - Cięcie na 8×2 segmentów (lub fallback)
     *  - Zapis debug_output.png z czerwoną siatką
     *  - Zwraca listę obiektów CompressedBitmap (zamiast JSON String!)
     */
    public List<CompressedBitmap> processImage(byte[] imageBytes) throws IOException {

        // 1. Bajty -> BufferedImage
        BufferedImage input;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
            input = ImageIO.read(bais);
        }
        if (input == null) {
            throw new IOException("Nie udało się wczytać obrazu z bajtów.");
        }

        int origWidth = input.getWidth();
        int origHeight = input.getHeight();

        // 2. Binaryzacja + usuwanie samotnych pikseli
        boolean[][] matrix = new boolean[origHeight][origWidth];
        for (int y = 0; y < origHeight; y++) {
            for (int x = 0; x < origWidth; x++) {
                Color c = new Color(input.getRGB(x, y), true);
                int r = c.getRed();
                int g = c.getGreen();
                int b = c.getBlue();

                // Prosty próg
                boolean val = (r < 200 && g < 200);
                // Dodatkowo, jeżeli (r<150 && g<150 && b<120) => false
                if (r < 150 && g < 150 && b < 120) {
                    val = false;
                }
                matrix[y][x] = val;
            }
        }
        // Usuwamy samotne piksele
        matrix = removeLonelyPixels(matrix);

        // 3. Usuwanie lewego pustego marginesu (threshold=10 czarnych pikseli)
        int leftMargin = findLeftMargin(matrix);
        if (leftMargin > 0) {
            matrix = cutLeft(matrix, leftMargin);
        }

        int width = matrix[0].length;
        int height = matrix.length;

        // 4. Szukamy 7 linii poziomych (8 stref)
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

        // 6. Tniemy na 8×2 (lub fallback)
        List<CompressedBitmap> resultList;
        if (!fallback) {
            resultList = cutIntoSegments(matrix, hLines, vLine);
        } else {
            resultList = cutEqually(matrix);
        }

        // 7. Rysowanie i zapis debug_output.png
        // Tworzymy kopię oryginalnego obrazu i odcinamy leftMargin
        BufferedImage debugImg = copyBufferedImage(input);
        debugImg = debugImg.getSubimage(leftMargin, 0, width, height);

        drawDebugLines(debugImg, hLines, vLine, fallback);
        ImageIO.write(debugImg, "png", new File("debug_output.png"));

        // 8. Zwracamy listę obiektów CompressedBitmap
        // (Spring Boot / Jackson zamieni to na JSON w kontrolerze)
        return resultList;
    }

    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // Znajdowanie 7 linii => 8 segmentów
    private List<Integer> find7HorizontalLines(boolean[][] matrix) {
        int h = matrix.length;
        int w = matrix[0].length;

        double rowThreshold = w * 0.01;
        double minBlockHeight = 0.05 * h;

        boolean[] isWhite = new boolean[h];
        for (int y = 0; y < h; y++) {
            int blackCount = 0;
            for (int x = 0; x < w; x++) {
                if (matrix[y][x]) blackCount++;
            }
            isWhite[y] = (blackCount < rowThreshold);
        }

        List<WhiteBlock> blocks = new ArrayList<>();
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

        if (blocks.size() < 7) {
            throw new RuntimeException("Zbyt mało białych bloków (>=5%): " + blocks.size());
        }

        List<WhiteBlock> used = blocks.subList(0, 7);
        List<Integer> lines = new ArrayList<>(7);

        for (int i = 0; i < 7; i++) {
            WhiteBlock wb = used.get(i);
            int startY = wb.start;
            int endY = wb.end;
            int blockH = endY - startY + 1;

            double ratio = 0.5;
            if (i == 0) {
                ratio = 0.7; // top
            } else if (i == 6) {
                ratio = 0.3; // bottom
            }

            int cutLine = (int)(startY + ratio * blockH);
            lines.add(cutLine);
        }
        lines.sort(Integer::compareTo);
        return lines;
    }

    private static class WhiteBlock {
        int start, end;
        WhiteBlock(int s, int e) { start = s; end = e; }
    }

    // -------------------------------------------------------------------------
    // Tniemy 8×2 lub fallback
    private List<CompressedBitmap> cutIntoSegments(boolean[][] matrix, List<Integer> hLines, int vLine) {
        int h = matrix.length, w = matrix[0].length;

        // Upewnij się, że hLines są posortowane
        hLines.sort(Integer::compareTo);

        // Budujemy granice poziome: [0, hLine1, hLine2, ..., hLine7, h]
        List<Integer> finalY = new ArrayList<>();
        finalY.add(0);
        finalY.addAll(hLines);
        finalY.add(h);

        int halfW = w / 2;
        // Zbieramy segmenty osobno dla lewej i prawej kolumny
        List<boolean[][]> leftSubMatrices = new ArrayList<>();
        List<boolean[][]> rightSubMatrices = new ArrayList<>();

        // Przetwarzamy tylko segmenty odpowiadające głównym wykresom EKG:
        // pomijamy pierwszy segment (od 0 do hLine1) oraz ostatni (od hLine7 do h)
        for (int i = 1; i < finalY.size() - 2; i++) {  // przy finalY.size()==9 => i = 1..6 (6 segmentów pionowo)
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

        // Łączymy segmenty – najpierw lewa kolumna (I, II, III, aVR, aVL, aVF), potem prawa (V1, V2, V3, V4, V5, V6)
        List<boolean[][]> subMatrices = new ArrayList<>();
        subMatrices.addAll(leftSubMatrices);
        subMatrices.addAll(rightSubMatrices);

        // Wyznaczamy minimalne wymiary spośród 12 głównych wykresów EKG
        int minH = subMatrices.stream().mapToInt(m -> m.length).min().orElse(0);
        int minW = subMatrices.stream().mapToInt(m -> m[0].length).min().orElse(0);

        // Przycinamy każdy segment do (minH x minW), a następnie usuwamy 5% z lewej i prawej (ze względu na artefakty)
        List<CompressedBitmap> list = new ArrayList<>();
        for (boolean[][] sub : subMatrices) {
            boolean[][] trimmed = trimToSize(sub, minH, minW);
            trimmed = trimLeftRight(trimmed, 0.05); // usuń 5% z obu stron
            int[] compressed = compressBooleanMatrix(trimmed);
            CompressedBitmap cb = new CompressedBitmap();
            cb.width = trimmed[0].length;
            cb.height = trimmed.length;
            cb.n = compressed.length;
            cb.data = compressed;
            list.add(cb);
        }
        return list;
    }

    private List<CompressedBitmap> cutEqually(boolean[][] matrix) {
        int h = matrix.length, w = matrix[0].length;
        int rowH = h / 8;  // oryginalny podział na 8 rzędów
        int colW = w / 2;
        // Zbieramy segmenty oddzielnie dla lewej i prawej kolumny
        List<boolean[][]> leftSubMatrices = new ArrayList<>();
        List<boolean[][]> rightSubMatrices = new ArrayList<>();

        // Pomijamy pierwszy (row = 0) i ostatni (row = 7) rząd – pozostają 6 rzędów (główne wykresy)
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

        // Łączymy – najpierw segmenty z lewej kolumny, potem te z prawej
        List<boolean[][]> subMatrices = new ArrayList<>();
        subMatrices.addAll(leftSubMatrices);
        subMatrices.addAll(rightSubMatrices);

        // Wyznaczamy minimalne wymiary spośród 12 głównych wykresów EKG
        int minH = subMatrices.stream().mapToInt(m -> m.length).min().orElse(0);
        int minW = subMatrices.stream().mapToInt(m -> m[0].length).min().orElse(0);

        List<CompressedBitmap> list = new ArrayList<>();
        for (boolean[][] sub : subMatrices) {
            boolean[][] trimmed = trimToSize(sub, minH, minW);
            trimmed = trimLeftRight(trimmed, 0.05); // usuń 5% z lewej i prawej
            int[] compressed = compressBooleanMatrix(trimmed);
            CompressedBitmap cb = new CompressedBitmap();
            cb.width = trimmed[0].length;
            cb.height = trimmed.length;
            cb.n = compressed.length;
            cb.data = compressed;
            list.add(cb);
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Kompresja boole[][] -> int[]
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

    // -------------------------------------------------------------------------
    // Rysowanie debug_output.png
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

    // Klasa sub–obrazu
    public static class CompressedBitmap {
        public int width;
        public int height;
        public int n;
        public int[] data;

        // Gettery/settery jeśli chcesz, ale dla Jacksona i publicznych pól wystarczy public:
        // public int getWidth(){return width;}
        // ...
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
}
