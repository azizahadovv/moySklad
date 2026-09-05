package uz.kassa.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import uz.kassa.bot.handlers.AdminHandler;
import uz.kassa.bot.handlers.BuxgalterHandler;
import uz.kassa.bot.handlers.KassirHandler;
import uz.kassa.domain.*;
import uz.kassa.repo.AppUserRepo;
import uz.kassa.repo.OperationRepo;
import uz.kassa.repo.SubmissionRepo;
import uz.kassa.service.*;
import java.util.Optional;
import static uz.kassa.bot.TextUtil.*;

/**
 * Skrinshotdan summa o'qish: ko'p bosqichli Tesseract OCR (binarizatsiya, PSM variantlari) va namuna saqlash.
 * (Router dan ajratilgan — xatti-harakat o'zgarmagan.)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OcrEngine {



    /**
     * KO'P O'TISHLI OCR: Tesseract katta qalin oq raqamni gradient fonda ba'zan
     * yo'qotadi («250 000.00» → «ae»). Shuning uchun rasm bir necha ko'rinishda
     * o'qiladi va summa (≥10 000) topilgan BIRINCHI natija olinadi:
     *   1) asl rasm, psm 6;
     *   2) oq matn → qora-oq (yorug' piksel = siyoh), psm 6;
     *   3) qora matn → qora-oq (qorong'i piksel = siyoh), psm 6;
     *   4) 50% kichraytirilgan asl rasm, psm 6 (juda katta shrift uchun);
     *   5) asl rasm, psm 11 (siyrak matn).
     * Hech birida summa chiqmasa — eng uzun matn qaytariladi.
     */
    String ocrMultiPass(java.io.File img) throws Exception {
        java.util.List<java.io.File> tmp = new java.util.ArrayList<>();
        String best = "";
        try {
            java.awt.image.BufferedImage src = null;
            try { src = javax.imageio.ImageIO.read(img); } catch (Exception ignore) { }

            record Pass(java.io.File f, String psm) {}
            java.util.List<Pass> passes = new java.util.ArrayList<>();
            passes.add(new Pass(img, "6"));
            if (src != null) {
                // Tartib — real skrinshotlarda qaysi o'tish ishlaganiga qarab:
                // katta qalin raqam (Click ilovasi) 33% kichraytirilganda o'qildi.
                java.io.File third = scale(src, 3);            tmp.add(third);     passes.add(new Pass(third, "6"));
                java.io.File half = scale(src, 2);             tmp.add(half);      passes.add(new Pass(half, "6"));
                java.io.File brightInk = binarize(src, true);  tmp.add(brightInk); passes.add(new Pass(brightInk, "6"));
                java.io.File darkInk = binarize(src, false);   tmp.add(darkInk);   passes.add(new Pass(darkInk, "6"));
                try {
                    java.io.File brightHalf = scale(javax.imageio.ImageIO.read(brightInk), 2);
                    tmp.add(brightHalf);                                           passes.add(new Pass(brightHalf, "6"));
                } catch (Exception ignore) { }
            }
            passes.add(new Pass(img, "11"));

            StringBuilder diag = new StringBuilder();
            int n = 0;
            for (Pass ps : passes) {
                String text = tesseract(ps.f(), ps.psm());
                if (text.length() > best.length()) best = text;
                String cleaned = text
                        .replaceAll("\\b\\d{1,2}[.,/]\\d{1,2}[.,/]\\d{2,4}\\b", " ")
                        .replaceAll("\\b\\d{1,2}:\\d{2}\\b", " ");
                var sums = extractSums(cleaned);
                diag.append(" #").append(++n).append("(psm").append(ps.psm()).append(")=")
                    .append(sums.isEmpty() ? "-" : sums.toString());
                if (!sums.isEmpty()) {
                    log.info("OCR o'tishlar:{}", diag);
                    return text;
                }
            }
            log.info("OCR o'tishlar (summa yo'q):{}", diag);
            return best;
        } finally {
            for (java.io.File f : tmp) f.delete();
        }
    }


    String tesseract(java.io.File f, String psm) throws Exception {
        Process p = new ProcessBuilder("tesseract", f.getAbsolutePath(), "stdout", "--psm", psm, "-l", "eng")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String text = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        p.waitFor();
        return text;
    }


    /** Qora-oq: brightInk=true — yorug' (oq) matn siyoh bo'ladi (qorong'i/rangli fon uchun). */
    java.io.File binarize(java.awt.image.BufferedImage src, boolean brightInk) throws Exception {
        int w = src.getWidth(), h = src.getHeight();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        var r = out.getRaster();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                int l = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
                boolean ink = brightInk ? l > 190 : l < 90;
                r.setSample(x, y, 0, ink ? 0 : 255);
            }
        java.io.File f = java.io.File.createTempFile("tgocr-bw-", ".png");
        javax.imageio.ImageIO.write(out, "png", f);
        return f;
    }


    /** Diagnostika: yuklab olingan skrinshotning FAQAT OXIRGISI logs/ocr/ da turadi —
     *  yangisi kelganda avvalgisi o'chiriladi (foydalanuvchi qarori: rasmlar yig'ilmasin,
     *  faqat ma'lumot olinsin). OCR o'qimagan oxirgi rasmni qo'lda tekshirish uchun. */
    void keepOcrSample(java.io.File img, Integer msgId) {
        try {
            java.io.File dir = new java.io.File("logs/ocr");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            java.io.File[] old = dir.listFiles();
            if (old != null) for (java.io.File f : old) f.delete();
            java.nio.file.Files.copy(img.toPath(), new java.io.File(dir,
                    System.currentTimeMillis() / 1000 + "-msg" + msgId + ".jpg").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.debug("OCR nusxa saqlanmadi: {}", e.getMessage());
        }
    }


    java.io.File halfScale(java.awt.image.BufferedImage src) throws Exception {
        return scale(src, 2);
    }


    java.io.File scale(java.awt.image.BufferedImage src, int div) throws Exception {
        int w = Math.max(1, src.getWidth() / div), h = Math.max(1, src.getHeight() / div);
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        java.io.File f = java.io.File.createTempFile("tgocr-s" + div + "-", ".png");
        javax.imageio.ImageIO.write(out, "png", f);
        return f;
    }


    /** Matndan pul summalarini ajratish: «2.030.000,00», «24 936 377.74», «12804310»,
     *  hatto OCR ning «1382 270.25» kabi notekis guruhlashi ham. Usul: avval raqam
     *  bo'laklari orasidagi minglik ajratkichlar (bo'sh joy/nuqta + roppa-rosa 3 raqam)
     *  YOPISHTIRILADI, keyin yaxlit son o'qiladi. Tiyin tashlanadi; 10 000 so'mdan
     *  kichigi e'tiborsiz (karta raqami bo'laklari 9860/1947 ham shu filtrda qoladi). */
    /** extractSums bilan bir xil, lekin TIYINDA qaytaradi («12 235.45» → 1223545;
     *  «250 000.00» → 25000000). 10 000 so'mdan kichigi e'tiborsiz. */
    java.util.LinkedHashSet<Long> extractSumsTiyin(String s) {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        String joined = s, prev;
        do {
            prev = joined;
            joined = joined.replaceAll("(?<=\\d)[ \\u00A0.](?=\\d{3}(?!\\d))", "");
        } while (!joined.equals(prev));
        var matcher = java.util.regex.Pattern
                .compile("(\\d{4,})(?:[.,](\\d{1,2}))?")
                .matcher(joined);
        while (matcher.find()) {
            try {
                long v = Long.parseLong(matcher.group(1));
                String f = matcher.group(2) == null ? "0" : (matcher.group(2).length() == 1 ? matcher.group(2) + "0" : matcher.group(2));
                long t = v * 100 + Long.parseLong(f);
                if (v >= 10_000) out.add(t);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }


    /** Balans qatorida VALYUTA bilan kelgan summa — 10 000 dan kichik bo'lsa ham, 0 ham:
     *  «0.00 UZS», «0 сум», «5 000 so'm», «1 250,50 UZS». Karta raqami bo'laklari valyutasiz
     *  keladi, shuning uchun bu yerda xavf yo'q. TIYINDA qaytaradi. */
    java.util.LinkedHashSet<Long> extractCurrencyTiyin(String s) {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        String joined = s, prev;
        do {
            prev = joined;
            joined = joined.replaceAll("(?<=\\d)[ \\u00A0.](?=\\d{3}(?!\\d))", "");
        } while (!joined.equals(prev));
        var matcher = java.util.regex.Pattern
                .compile("(?<!\\d)(\\d{1,})(?:[.,](\\d{1,2}))?\\s*(?:uzs|сум|сўм|so['’`‘]?m|som|sum)\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(joined);
        while (matcher.find()) {
            try {
                long v = Long.parseLong(matcher.group(1));
                String f = matcher.group(2) == null ? "0" : (matcher.group(2).length() == 1 ? matcher.group(2) + "0" : matcher.group(2));
                out.add(v * 100 + Long.parseLong(f));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }


    /** Qatordagi YAGONA son (valyutasiz) — balans yorlig'i qatorida «Umumiy balans 0» kabi.
     *  Ikki va undan ko'p son bo'lsa (karta raqami, sana...) bo'sh qaytaradi. */
    java.util.LinkedHashSet<Long> extractSoleNumberTiyin(String s) {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        String joined = s, prev;
        do {
            prev = joined;
            joined = joined.replaceAll("(?<=\\d)[ \\u00A0.](?=\\d{3}(?!\\d))", "");
        } while (!joined.equals(prev));
        var matcher = java.util.regex.Pattern.compile("(?<!\\d)(\\d{1,})(?:[.,](\\d{1,2}))?(?!\\d)").matcher(joined);
        int n = 0; long val = 0;
        while (matcher.find()) {
            n++;
            try {
                long v = Long.parseLong(matcher.group(1));
                String f = matcher.group(2) == null ? "0" : (matcher.group(2).length() == 1 ? matcher.group(2) + "0" : matcher.group(2));
                val = v * 100 + Long.parseLong(f);
            } catch (NumberFormatException ignored) { return out; }
        }
        if (n == 1) out.add(val);
        return out;
    }


    java.util.LinkedHashSet<Long> extractSums(String s) {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        String joined = s, prev;
        do {
            prev = joined;
            joined = joined.replaceAll("(?<=\\d)[ \\u00A0.](?=\\d{3}(?!\\d))", "");
        } while (!joined.equals(prev));
        var matcher = java.util.regex.Pattern
                .compile("\\d{4,}(?:[.,]\\d{1,2})?")
                .matcher(joined);
        while (matcher.find()) {
            String t = matcher.group().replaceAll("[.,]\\d{1,2}$", "");   // tiyin tashlanadi
            try {
                long v = Long.parseLong(t);
                if (v >= 10_000) out.add(v);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

}
