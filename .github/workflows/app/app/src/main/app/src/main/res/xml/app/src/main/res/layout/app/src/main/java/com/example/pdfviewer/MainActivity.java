package com.example.pdfviewer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ImageView pageView;
    private TextView pageInfo;
    private ScrollView scrollV;
    private PdfRenderer renderer;
    private File pdfFile;
    private int pageIndex = 0;
    private float zoom = 1f;
    private float pinch = 1f;
    private ScaleGestureDetector scaleDetector;
    private ActivityResultLauncher<String[]> picker;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        PDFBoxResourceLoader.init(getApplicationContext());

        pageView = findViewById(R.id.pageView);
        pageInfo = findViewById(R.id.pageInfo);
        scrollV = findViewById(R.id.scrollV);

        picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) loadPdf(uri);
        });

        findViewById(R.id.btnOpen).setOnClickListener(v -> picker.launch(new String[]{"application/pdf"}));
        findViewById(R.id.btnCopy).setOnClickListener(v -> copyText());
        findViewById(R.id.btnShare).setOnClickListener(v -> sharePdf());
        findViewById(R.id.btnPrev).setOnClickListener(v -> changePage(-1));
        findViewById(R.id.btnNext).setOnClickListener(v -> changePage(1));
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> setZoom(zoom + 0.5f));
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> setZoom(zoom - 0.5f));

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                pinch = 1f;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                pinch *= d.getScaleFactor();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                setZoom(zoom * pinch);
            }
        });

        Uri data = getIntent().getData();
        if (data != null) loadPdf(data);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String getName(Uri uri) {
        String name = "document.pdf";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.getString(i) != null) name = c.getString(i);
            }
        } catch (Exception ignored) {
        }
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (!name.toLowerCase().endsWith(".pdf")) name += ".pdf";
        return name;
    }

    private void loadPdf(Uri uri) {
        final String name = getName(uri);
        bg.execute(() -> {
            try {
                File f = new File(getCacheDir(), name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                runOnUiThread(() -> openFile(f));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Could not open this PDF"));
            }
        });
    }

    private void openFile(File f) {
        try {
            if (renderer != null) renderer.close();
            pdfFile = f;
            renderer = new PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY));
            pageIndex = 0;
            zoom = 1f;
            showPage();
        } catch (Exception e) {
            toast("Could not open this PDF");
        }
    }

    private void showPage() {
        if (renderer == null) return;
        int w = (int) (getResources().getDisplayMetrics().widthPixels * zoom);
        PdfRenderer.Page page = renderer.openPage(pageIndex);
        int h = (int) ((float) w * page.getHeight() / page.getWidth());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        page.close();
        pageView.setImageBitmap(bmp);
        pageInfo.setText("Page " + (pageIndex + 1) + " / " + renderer.getPageCount());
    }

    private void changePage(int step) {
        if (renderer == null) return;
        int next = pageIndex + step;
        if (next < 0 || next >= renderer.getPageCount()) return;
        pageIndex = next;
        showPage();
        scrollV.scrollTo(0, 0);
    }

    private void setZoom(float z) {
        if (renderer == null) return;
        z = Math.max(1f, Math.min(3f, z));
        if (z == zoom) return;
        zoom = z;
        showPage();
    }

    private void copyText() {
        if (pdfFile == null) {
            toast("Open a PDF first");
            return;
        }
        final int page = pageIndex + 1;
        toast("Reading text...");
        bg.execute(() -> {
            try (PDDocument doc = PDDocument.load(pdfFile)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc).trim();
                runOnUiThread(() -> showText(text, page));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Could not read text"));
            }
        });
    }

    private void showText(String text, int page) {
        if (text.isEmpty()) {
            toast("No text on this page (it may be a scanned picture)");
            return;
        }
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextIsSelectable(true);
        tv.setPadding(40, 30, 40, 30);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Page " + page + " text")
                .setView(sv)
                .setPositiveButton("Copy all", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("pdf text", text));
                    toast("Copied!");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void sharePdf() {
        if (pdfFile == null) {
            toast("Open a PDF first");
            return;
        }
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share PDF"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (renderer != null) renderer.close();
        } catch (Exception ignored) {
        }
    }
}
