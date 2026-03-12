/**
 * AARExporter.java
 *
 * Export utilities for After Action Review summaries.
 * Supports PDF and plain text formats for offline sharing.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Export AAR summary to PDF using Android PdfDocument API</li>
 *   <li>Export AAR summary to plain text format</li>
 *   <li>Format output according to TC 7-0.1 structure</li>
 *   <li>Support sharing via Android Intent system</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Export layer utility. Called from SummaryFragment for user-initiated exports.
 *
 * <p>Thread Safety:
 * PDF generation is blocking and should be called on background thread.
 * Text export is lightweight and can run on main thread.
 *
 * <p>Air-Gap Compliance:
 * All exports stored locally. No cloud upload.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-04
 */
package com.stelliq.aria.export;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.FileProvider;

import com.stelliq.aria.model.AARSummary;
import com.stelliq.aria.util.Constants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Export utilities for AAR summaries to various formats.
 */
public class AARExporter {

    private static final String TAG = Constants.LOG_TAG_UI;

    // WHY: US Letter size in points (72 points per inch)
    private static final int PAGE_WIDTH = 612;   // 8.5 inches
    private static final int PAGE_HEIGHT = 792;  // 11 inches
    private static final int MARGIN = 72;        // 1 inch margins

    // WHY: Text formatting
    private static final float TITLE_SIZE = 24f;
    private static final float HEADING_SIZE = 14f;
    private static final float BODY_SIZE = 11f;
    private static final float LINE_SPACING = 16f;

    /**
     * Private constructor — use static methods.
     */
    private AARExporter() {
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PDF EXPORT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Exports AAR summary to PDF file.
     *
     * <p>PERF: PDF generation is blocking. Call on background thread.
     *
     * @param context   Application context
     * @param summary   AAR summary to export
     * @param transcript Optional full transcript to include
     * @return PDF file, or null if export failed
     */
    @WorkerThread
    @Nullable
    public static File exportToPdf(@NonNull Context context,
                                   @NonNull AARSummary summary,
                                   @Nullable String transcript) {
        Log.i(TAG, "[AARExporter.exportToPdf] Starting PDF export");

        PdfDocument document = new PdfDocument();
        Paint titlePaint = new Paint();
        Paint headingPaint = new Paint();
        Paint bodyPaint = new Paint();

        // WHY: Configure paints for text rendering
        titlePaint.setColor(Color.BLACK);
        titlePaint.setTextSize(TITLE_SIZE);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titlePaint.setAntiAlias(true);

        headingPaint.setColor(Color.parseColor("#1a3e5c"));  // ARIA dark blue
        headingPaint.setTextSize(HEADING_SIZE);
        headingPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        headingPaint.setAntiAlias(true);

        bodyPaint.setColor(Color.BLACK);
        bodyPaint.setTextSize(BODY_SIZE);
        bodyPaint.setAntiAlias(true);

        try {
            // WHY: Create first page
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                    PAGE_WIDTH, PAGE_HEIGHT, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            float y = MARGIN;
            int contentWidth = PAGE_WIDTH - (2 * MARGIN);

            // WHY: Draw title
            canvas.drawText("AFTER ACTION REVIEW", MARGIN, y + TITLE_SIZE, titlePaint);
            y += TITLE_SIZE + 10;

            // WHY: Draw date
            String dateStr = new SimpleDateFormat("MMMM d, yyyy HH:mm", Locale.US)
                    .format(new Date());
            bodyPaint.setColor(Color.GRAY);
            canvas.drawText(dateStr, MARGIN, y + BODY_SIZE, bodyPaint);
            bodyPaint.setColor(Color.BLACK);
            y += BODY_SIZE + 30;

            // WHY: Draw separator line
            Paint linePaint = new Paint();
            linePaint.setColor(Color.parseColor("#FF9100"));  // ARIA orange
            linePaint.setStrokeWidth(2f);
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint);
            y += 20;

            // WHY: Draw each AAR section
            y = drawSection(canvas, "1. WHAT WAS PLANNED",
                    summary.getWhatWasPlanned(), y, contentWidth, headingPaint, bodyPaint);

            y = drawSection(canvas, "2. WHAT HAPPENED",
                    summary.getWhatHappened(), y, contentWidth, headingPaint, bodyPaint);

            y = drawSection(canvas, "3. WHY IT HAPPENED",
                    summary.getWhyItHappened(), y, contentWidth, headingPaint, bodyPaint);

            y = drawSection(canvas, "4. HOW TO IMPROVE",
                    summary.getHowToImprove(), y, contentWidth, headingPaint, bodyPaint);

            // WHY: Add transcript if provided and fits
            if (transcript != null && !transcript.isEmpty() && y < PAGE_HEIGHT - MARGIN - 100) {
                y += 20;
                canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint);
                y += 20;

                canvas.drawText("TRANSCRIPT", MARGIN, y + HEADING_SIZE, headingPaint);
                y += HEADING_SIZE + 10;

                // WHY: Draw transcript with word wrapping
                y = drawWrappedText(canvas, transcript, MARGIN, y, contentWidth, bodyPaint, PAGE_HEIGHT - MARGIN);
            }

            // WHY: Draw footer
            bodyPaint.setColor(Color.GRAY);
            bodyPaint.setTextSize(9f);
            canvas.drawText("Generated by ARIA - Army After Action Review Assistant",
                    MARGIN, PAGE_HEIGHT - 30, bodyPaint);
            bodyPaint.setTextSize(BODY_SIZE);
            bodyPaint.setColor(Color.BLACK);

            document.finishPage(page);

            // WHY: Write PDF to file
            File outputDir = context.getExternalFilesDir("exports");
            if (outputDir == null) {
                outputDir = context.getFilesDir();
            }
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            String filename = "AAR_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".pdf";
            File outputFile = new File(outputDir, filename);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                document.writeTo(fos);
            }

            Log.i(TAG, "[AARExporter.exportToPdf] PDF saved to: " + outputFile.getAbsolutePath());
            return outputFile;

        } catch (IOException e) {
            Log.e(TAG, "[AARExporter.exportToPdf] Failed: " + e.getMessage(), e);
            return null;
        } finally {
            document.close();
        }
    }

    /**
     * Draws a section with heading and wrapped body text.
     *
     * @return Y position after drawing
     */
    private static float drawSection(Canvas canvas, String heading, String body,
                                     float startY, int contentWidth,
                                     Paint headingPaint, Paint bodyPaint) {
        float y = startY;

        // WHY: Draw heading
        canvas.drawText(heading, MARGIN, y + HEADING_SIZE, headingPaint);
        y += HEADING_SIZE + 8;

        // WHY: Draw body with word wrapping
        y = drawWrappedText(canvas, body, MARGIN, y, contentWidth, bodyPaint, PAGE_HEIGHT - MARGIN);

        y += 20;  // Section spacing
        return y;
    }

    /**
     * Draws text with word wrapping.
     *
     * @return Y position after drawing
     */
    private static float drawWrappedText(Canvas canvas, String text, float x, float y,
                                         int maxWidth, Paint paint, float maxY) {
        if (text == null || text.isEmpty()) {
            return y;
        }

        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        float lineY = y;

        for (String word : words) {
            String testLine = line.length() > 0 ? line + " " + word : word;
            float testWidth = paint.measureText(testLine);

            if (testWidth > maxWidth) {
                // WHY: Draw current line and start new one
                canvas.drawText(line.toString(), x, lineY + BODY_SIZE, paint);
                lineY += LINE_SPACING;

                // WHY: Check if we've exceeded page
                if (lineY > maxY) {
                    canvas.drawText("...", x, lineY + BODY_SIZE, paint);
                    return lineY;
                }

                line = new StringBuilder(word);
            } else {
                if (line.length() > 0) {
                    line.append(" ");
                }
                line.append(word);
            }
        }

        // WHY: Draw remaining text
        if (line.length() > 0) {
            canvas.drawText(line.toString(), x, lineY + BODY_SIZE, paint);
            lineY += LINE_SPACING;
        }

        return lineY;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEXT EXPORT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Exports AAR summary to plain text.
     *
     * @param summary AAR summary to export
     * @return Formatted text string
     */
    @NonNull
    public static String exportToText(@NonNull AARSummary summary) {
        StringBuilder sb = new StringBuilder();

        sb.append("═══════════════════════════════════════════════════════\n");
        sb.append("            AFTER ACTION REVIEW\n");
        sb.append("═══════════════════════════════════════════════════════\n\n");

        sb.append("Date: ");
        sb.append(new SimpleDateFormat("MMMM d, yyyy HH:mm", Locale.US).format(new Date()));
        sb.append("\n\n");

        sb.append("───────────────────────────────────────────────────────\n");
        sb.append("1. WHAT WAS PLANNED\n");
        sb.append("───────────────────────────────────────────────────────\n\n");
        sb.append(summary.getWhatWasPlanned());
        sb.append("\n\n");

        sb.append("───────────────────────────────────────────────────────\n");
        sb.append("2. WHAT HAPPENED\n");
        sb.append("───────────────────────────────────────────────────────\n\n");
        sb.append(summary.getWhatHappened());
        sb.append("\n\n");

        sb.append("───────────────────────────────────────────────────────\n");
        sb.append("3. WHY IT HAPPENED\n");
        sb.append("───────────────────────────────────────────────────────\n\n");
        sb.append(summary.getWhyItHappened());
        sb.append("\n\n");

        sb.append("───────────────────────────────────────────────────────\n");
        sb.append("4. HOW TO IMPROVE\n");
        sb.append("───────────────────────────────────────────────────────\n\n");
        sb.append(summary.getHowToImprove());
        sb.append("\n\n");

        sb.append("═══════════════════════════════════════════════════════\n");
        sb.append("Generated by ARIA - Army After Action Review Assistant\n");
        sb.append("═══════════════════════════════════════════════════════\n");

        return sb.toString();
    }

    /**
     * Exports AAR summary to plain text with transcript.
     *
     * @param summary    AAR summary to export
     * @param transcript Full transcript text
     * @return Formatted text string
     */
    @NonNull
    public static String exportToText(@NonNull AARSummary summary, @Nullable String transcript) {
        StringBuilder sb = new StringBuilder(exportToText(summary));

        if (transcript != null && !transcript.isEmpty()) {
            sb.append("\n");
            sb.append("───────────────────────────────────────────────────────\n");
            sb.append("TRANSCRIPT\n");
            sb.append("───────────────────────────────────────────────────────\n\n");
            sb.append(transcript);
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Saves text export to file.
     *
     * @param context Application context
     * @param text    Text content to save
     * @return Text file, or null if save failed
     */
    @Nullable
    public static File saveTextToFile(@NonNull Context context, @NonNull String text) {
        try {
            File outputDir = context.getExternalFilesDir("exports");
            if (outputDir == null) {
                outputDir = context.getFilesDir();
            }
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            String filename = "AAR_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".txt";
            File outputFile = new File(outputDir, filename);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(text.getBytes());
            }

            Log.i(TAG, "[AARExporter.saveTextToFile] Text saved to: " + outputFile.getAbsolutePath());
            return outputFile;

        } catch (IOException e) {
            Log.e(TAG, "[AARExporter.saveTextToFile] Failed: " + e.getMessage(), e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHARING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a share intent for a file.
     *
     * @param context Application context
     * @param file    File to share
     * @param mimeType MIME type (e.g., "application/pdf", "text/plain")
     * @return Share intent, or null if URI creation failed
     */
    @Nullable
    public static Intent createShareIntent(@NonNull Context context,
                                           @NonNull File file,
                                           @NonNull String mimeType) {
        try {
            // WHY: Use FileProvider for secure file sharing
            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    file
            );

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(mimeType);
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "After Action Review - " +
                    new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            return Intent.createChooser(shareIntent, "Share AAR via");

        } catch (Exception e) {
            Log.e(TAG, "[AARExporter.createShareIntent] Failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Creates a share intent for text content.
     *
     * @param text Text content to share
     * @return Share intent
     */
    @NonNull
    public static Intent createTextShareIntent(@NonNull String text) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "After Action Review - " +
                new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));

        return Intent.createChooser(shareIntent, "Share AAR via");
    }
}
