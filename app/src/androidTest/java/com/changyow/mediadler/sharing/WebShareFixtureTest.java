package com.changyow.mediadler.sharing;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in owned MediaStore files for independent browser QA: -e webShareFixture prepare|cleanup. */
@RunWith(AndroidJUnit4.class)
public class WebShareFixtureTest {
    @Test public void fixture() throws Exception {
        String action = InstrumentationRegistry.getArguments().getString("webShareFixture");
        assumeTrue("Fixture requires explicit opt-in", "prepare".equals(action) || "cleanup".equals(action));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("web_share_qa_fixture", Context.MODE_PRIVATE);
        JSONArray owned = new JSONArray(prefs.getString("uris", "[]"));
        if ("cleanup".equals(action)) {
            for (int i = 0; i < owned.length(); i++)
                context.getContentResolver().delete(Uri.parse(owned.getString(i)), null, null);
            assertTrue(context.deleteSharedPreferences("web_share_qa_fixture"));
            return;
        }
        assertEquals("Clean the previous owned fixture first", 0, owned.length());
        String folder = "web-share-qa-" + UUID.randomUUID();
        JSONObject manifest = new JSONObject().put("folder", folder);
        JSONArray files = new JSONArray();
        String[] names = {"測試 & 空白.txt", "子資料夾/package.zip", "slow-transfer.bin", "empty.bin", ".hidden.txt", "incomplete.part"};
        int[] sizes = {1024, 2048, 4 * 1024 * 1024, 0, 32, 32};
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            int slash = name.lastIndexOf('/');
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name.substring(slash + 1));
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/media-dler/" + folder + "/" + (slash < 0 ? "" : name.substring(0, slash + 1)));
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            assertNotNull(uri);
            owned.put(uri.toString());
            assertTrue(prefs.edit().putString("uris", owned.toString()).commit());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[8192];
            int position = 0;
            try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                assertNotNull(output);
                while (position < sizes[i]) {
                    int count = Math.min(block.length, sizes[i] - position);
                    for (int j = 0; j < count; j++) block[j] = (byte) ((position + j) % 251);
                    output.write(block, 0, count);
                    digest.update(block, 0, count);
                    position += count;
                }
            }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            files.put(new JSONObject().put("path", "/" + folder + "/" + name).put("size", sizes[i]).put("sha256", hash.toString()));
        }
        manifest.put("files", files);
        Bundle status = new Bundle(); status.putString("stream", "\nWEB_SHARE_FIXTURE " + manifest + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
