/*
    This file is part of eID Suite.
    Copyright (C) 2015 Egelke BVBA

    eID Suite is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    eID Suite is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with eID Suite.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.egelke.android.eid.view;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Messenger;
import android.os.Parcel;
import android.os.SystemClock;
import android.support.v7.app.ActionBarActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import net.egelke.android.eid.EidService;
import net.egelke.android.eid.R;
import net.egelke.android.eid.jca.BeIDKeyStoreStream;
import net.egelke.android.eid.jca.BeIDManagerFactoryParameters;
import net.egelke.android.eid.jca.BeIDProvider;
import net.egelke.android.eid.tls.EidSSLSocketFactory;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.spongycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.CharBuffer;
import java.security.KeyStore;
import java.security.Security;
import java.util.LinkedList;
import java.util.List;

import javax.net.SocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public class AuthActivity extends ActionBarActivity {

    private static final String TAG = "net.egelke.android.eid";

    private Messenger mEidService = null;
    private WebView webview;
    private String url;

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            mEidService = new Messenger(service);
        }

        public void onServiceDisconnected(ComponentName className) {
            mEidService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().requestFeature(Window.FEATURE_PROGRESS);
        super.onCreate(savedInstanceState);

        webview = new WebView(this);
        webview.getSettings().setJavaScriptEnabled(true);
        webview.setWebChromeClient(new WebChromeClient() {
            public void onProgressChanged(WebView view, int progress) {
                AuthActivity.this.setProgress(progress * 1000);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                AuthActivity.this.setTitle(getString(R.string.title_activity_auth) + ": " + title);
            }
        });
        webview.setWebViewClient(new MyWebViewClient());

        setContentView(webview);

        Intent intent = getIntent();
        if (intent.getAction() == Intent.ACTION_VIEW) {
            url = intent.getData().toString();
            webview.loadUrl(url);
        } else {
            webview.loadUrl("http://www.taxonweb.be/");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        //setup service
        bindService(new Intent(this, EidService.class), mConnection, Context.BIND_AUTO_CREATE);
    }

    private class MyWebViewClient extends WebViewClient {

        String cookies;

        @Override
        public WebResourceResponse shouldInterceptRequest(final WebView view, final String url) {
            if (!url.startsWith("https://certif.iamfas.belgium.be/fas/")) {
                Log.d(TAG, String.format("Getting %s in the default way", url));
                //get the cookie at the each run, lets hope it is on time.
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        cookies = CookieManager.getInstance().getCookie("https://certif.iamfas.belgium.be/fas/");
                    }
                }).start();
                return null;
            }
            try {
                URL path = new URL(url);
                HttpsURLConnection con = (HttpsURLConnection) path.openConnection();
                con.setInstanceFollowRedirects(false);
                con.setRequestProperty("Cookie", cookies);
                con.setRequestProperty("Referer", AuthActivity.this.url);
                con.setSSLSocketFactory(new EidSSLSocketFactory(mEidService));

                Log.d(TAG, String.format("Getting %s [Cookie=%s, Referer=%s]", url, cookies, AuthActivity.this.url));
                InputStream in = con.getInputStream();
                if (con.getResponseCode() == 200) {
                    Log.d(TAG, String.format("Got 200, returning data (%d)", con.getContentLength()));
                    String[] contentTypeParts = con.getContentType().split(";[ ]*");
                    return new WebResourceResponse(contentTypeParts[0], contentTypeParts[1], in);
                }if (con.getResponseCode() == 302) {
                    Log.d(TAG, "Got 302, Setting cookies and following");
                    //Update the cookies before we do the redirect
                    String iamfasPR=null;
                    List<String> setCookieValues = con.getHeaderFields().get("Set-Cookie");
                    for(String setCookieValue : setCookieValues) {
                        CookieManager.getInstance().setCookie(url, setCookieValue);
                        if (setCookieValue.startsWith("iamfasPR=")) {
                            iamfasPR = setCookieValue.split(";[ ]*")[0];
                        }
                    }

                    path = new URL(con.getHeaderField("Location"));
                    con = (HttpsURLConnection) path.openConnection();
                    con.setInstanceFollowRedirects(false);
                    con.setSSLSocketFactory(new EidSSLSocketFactory(mEidService));
                    String cookie[] = cookies.split(";[ ]*");
                    List<String> newCookies = new LinkedList<String>();
                    for(String newCookie : cookie) {
                        if (newCookie.startsWith("FASNODE")
                                || newCookie.startsWith("STORKNODE")
                                || newCookie.startsWith("iamfaslbPR")
                                || newCookie.startsWith("IAM3-FAS-JSESSIONID-PR"))
                            newCookies.add(newCookie);
                    }
                    newCookies.add(iamfasPR);
                    String newCookie = TextUtils.join("; ", newCookies);
                    con.setRequestProperty("Cookie", newCookie);
                    con.setRequestProperty("Referer", AuthActivity.this.url);

                    Log.d(TAG, String.format("Getting %s  [Cookie=%s, Referer=%s]", path.toString(), newCookie, AuthActivity.this.url));
                    in = con.getInputStream();

                    String[] contentTypeParts = con.getContentType().split(";[ ]*");
                    Log.d(TAG, String.format("Got %d %s (%d)", con.getResponseCode(), con.getResponseMessage(), con.getContentLength()));
                    return new WebResourceResponse(contentTypeParts[0], contentTypeParts[1], in);
                } else {
                    return null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed HTTP intercept", e);
                return null;
            }
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_auth, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_close) {

            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Check if the key event was the Back button and if there's history
        if ((keyCode == KeyEvent.KEYCODE_BACK) && webview.canGoBack()) {
            webview.goBack();
            return true;
        }
        // If it wasn't the Back key or there's no web page history, bubble up to the default
        // system behavior (probably exit the activity)
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();

        //tear down service
        if (mEidService != null) {
            unbindService(mConnection);
        }
    }
}
