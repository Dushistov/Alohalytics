/*******************************************************************************
 The MIT License (MIT)

 Copyright (c) 2014 Alexander Zolotarev <me@alex.bio> from Minsk, Belarus

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.
 *******************************************************************************/

package org.alohalytics;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

public class HttpTransport {

  // TODO(AlexZ): tune for larger files
  private final static int STREAM_BUFFER_SIZE = 1024 * 64;
  private final static String TAG = "Alohalytics-Http";
  // Globally accessible for faster unit-testing
  public static int TIMEOUT_IN_MILLISECONDS = 30000;

  public static Params run(final Params p) throws IOException, NullPointerException {
    if (p.httpMethod == null) {
      throw new NullPointerException("Please set valid HTTP method for request at Params.httpMethod field.");
    }
    HttpURLConnection connection = null;
    if (p.debugMode)
      Log.d(TAG, "Connecting to " + p.url);
    try {
      connection = (HttpURLConnection) new URL(p.url).openConnection(); // NullPointerException, MalformedUrlException, IOException
      // We DO NOT follow any redirects for safer data transfer.
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(TIMEOUT_IN_MILLISECONDS);
      connection.setReadTimeout(TIMEOUT_IN_MILLISECONDS);
      connection.setUseCaches(false);
      connection.setRequestMethod(p.httpMethod);
      if (p.basicAuthUser != null) {
        final String encoded = Base64.encodeToString((p.basicAuthUser + ":" + p.basicAuthPassword).getBytes(), Base64.NO_WRAP);
        connection.setRequestProperty("Authorization", "Basic " + encoded);
      }
      if (p.userAgent != null) {
        connection.setRequestProperty("User-Agent", p.userAgent);
      }
      if (p.cookies != null) {
        connection.setRequestProperty("Cookie", p.cookies);
      }
      if (p.inputFilePath != null || p.data != null) {
        // Send (POST, PUT...) data to the server.
        if (p.contentType == null) {
          throw new NullPointerException("Please set Content-Type for request.");
        }
        connection.setRequestProperty("Content-Type", p.contentType);
        if (p.contentEncoding != null) {
          connection.setRequestProperty("Content-Encoding", p.contentEncoding);
        }
        connection.setDoOutput(true);
        if (p.data != null) {
          connection.setFixedLengthStreamingMode(p.data.length);
          final OutputStream os = connection.getOutputStream();
          try {
            os.write(p.data);
          } finally {
            os.close();
          }
          if (p.debugMode)
            Log.d(TAG, "Sent " + p.httpMethod + " with content of size " + p.data.length);
        } else {
          final File file = new File(p.inputFilePath);
          assert (file.length() == (int) file.length());
          connection.setFixedLengthStreamingMode((int) file.length());
          final BufferedInputStream istream = new BufferedInputStream(new FileInputStream(file), STREAM_BUFFER_SIZE);
          final BufferedOutputStream ostream = new BufferedOutputStream(connection.getOutputStream(), STREAM_BUFFER_SIZE);
          final byte[] buffer = new byte[STREAM_BUFFER_SIZE];
          int bytesRead;
          while ((bytesRead = istream.read(buffer, 0, STREAM_BUFFER_SIZE)) > 0) {
            ostream.write(buffer, 0, bytesRead);
          }
          istream.close(); // IOException
          ostream.close(); // IOException
          if (p.debugMode)
            Log.d(TAG, "Sent " + p.httpMethod + " with file of size " + file.length());
        }
      }
      // GET data from the server or receive response body
      p.httpResponseCode = connection.getResponseCode();
      if (p.debugMode)
        Log.d(TAG, "Received HTTP " + p.httpResponseCode + " from server.");
      if (p.httpResponseCode >= 300 && p.httpResponseCode < 400) {
        p.receivedUrl = connection.getHeaderField("Location");
      } else {
        p.receivedUrl = connection.getURL().toString();
      }
      p.contentType = connection.getContentType();
      p.contentEncoding = connection.getContentEncoding();
      final Map<String, List<String>> headers = connection.getHeaderFields();
      if (headers != null && headers.containsKey("Set-Cookie")) {
        p.cookies = "";
        for (final String value : headers.get("Set-Cookie")) {
          if (value != null) {
            // Multiple Set-Cookie headers are normalized in C++ code.
            p.cookies += value + ", ";
          }
        }
      }
      // This implementation receives any data only if we have HTTP::OK (200).
      if (p.httpResponseCode == HttpURLConnection.HTTP_OK) {
        OutputStream ostream;
        if (p.outputFilePath != null) {
          ostream = new BufferedOutputStream(new FileOutputStream(p.outputFilePath), STREAM_BUFFER_SIZE);
        } else {
          ostream = new ByteArrayOutputStream(STREAM_BUFFER_SIZE);
        }
        // TODO(AlexZ): Add HTTP resume support in the future for partially downloaded files
        final BufferedInputStream istream = new BufferedInputStream(connection.getInputStream(), STREAM_BUFFER_SIZE);
        final byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        // gzip encoding is transparently enabled and we can't use Content-Length for
        // body reading if server has gzipped it.
        final String encoding = connection.getContentEncoding();
        int bytesExpected = (encoding != null && encoding.equalsIgnoreCase("gzip")) ? -1 : connection.getContentLength();
        int bytesRead;
        while ((bytesRead = istream.read(buffer, 0, STREAM_BUFFER_SIZE)) > 0) {
          if (bytesExpected == -1) {
            // Read everything if Content-Length is not known in advance.
            ostream.write(buffer, 0, bytesRead);
          } else {
            // Read only up-to Content-Length (sometimes servers/proxies add garbage at the end).
            if (bytesExpected < bytesRead) {
              ostream.write(buffer, 0, bytesExpected);
              break;
            }
            ostream.write(buffer, 0, bytesRead);
            bytesExpected -= bytesRead;
          }
        }
        istream.close(); // IOException
        ostream.close(); // IOException
        if (ostream instanceof ByteArrayOutputStream) {
          p.data = ((ByteArrayOutputStream) ostream).toByteArray();
        }
      }
    } finally {
      if (connection != null)
        connection.disconnect();
    }
    return p;
  }

  public static class Params {
    public String url = null;
    // Can be different from url in case of redirects.
    public String receivedUrl = null;
    public String httpMethod = null;
    // Should be specified for any request whose method allows non-empty body.
    // On return, contains received Content-Type or null.
    public String contentType = null;
    // Can be specified for any request whose method allows non-empty body.
    // On return, contains received Content-Encoding or null.
    public String contentEncoding = null;
    public byte[] data = null;
    // Send from input file if specified instead of data.
    public String inputFilePath = null;
    // Received data is stored here if not null or in data otherwise.
    public String outputFilePath = null;
    // Optionally client can override default HTTP User-Agent.
    public String userAgent = null;
    public String basicAuthUser = null;
    public String basicAuthPassword = null;
    public String cookies = null;
    public int httpResponseCode = -1;
    public boolean debugMode = false;

    // Simple GET request constructor.
    public Params(String url) {
      this.url = url;
      httpMethod = "GET";
    }

    public void setData(byte[] data, String contentType, String httpMethod) {
      this.data = data;
      this.contentType = contentType;
      this.httpMethod = httpMethod;
    }

    public void setInputFilePath(String path, String contentType, String httpMethod) {
      this.inputFilePath = path;
      this.contentType = contentType;
      this.httpMethod = httpMethod;
    }
  }


}
