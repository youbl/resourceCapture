package com.beinet.resourcecapture.captureTask.utils;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class DownHelper {
    private static String USER_AGENT = "Beinet Client 1.0";
    private static Config _defaultConfig;
    private static HashMap<String, String> _defaultHeader;
    private static int defaultTimeout = 5000;  // 5秒

    // 收集每次响应的set-cookie
    private static final Map<String, HttpCookie> _cookies = new HashMap<>();

    private DownHelper() {

    }

    static {
        _defaultConfig = InitConfig();

        _defaultHeader = new HashMap<>();
        _defaultHeader.put("Cache-Control", "no-cache");
        _defaultHeader.put("User-Agent", USER_AGENT);
        _defaultHeader.put("Accept-Encoding", "gzip");

        // 全局关闭重定向，因为底层只允许GET和HEAD，不允许POST等，
        HttpURLConnection.setFollowRedirects(false);

        // 设置ssl证书处理，避免证书问题导致异常
        try {
            SSLContext sslcontext = SSLContext.getInstance("TLSv1.2");//, "DTLSv1.2");
            TrustManager[] tm = {new X509TrustManagerExt()};
            sslcontext.init(null, tm, new SecureRandom());
            HostnameVerifier ignoreHostnameVerifier = (s, sslsession) -> {
                System.out.println("WARNING: Hostname is not matched for cert.");
                return true;
            };
            HttpsURLConnection.setDefaultHostnameVerifier(ignoreHostnameVerifier);
            HttpsURLConnection.setDefaultSSLSocketFactory(sslcontext.getSocketFactory());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 设置http请求的超时时间
     *
     * @param millisecond 毫秒
     */
    public static void setDefaultTimeout(int millisecond) {
        defaultTimeout = millisecond;
    }

    /**
     * 返回http请求的超时时间
     *
     * @return 毫秒
     */
    public static int getDefaultTimeout() {
        return defaultTimeout;
    }


    /**
     * GET获取url二进制内容，并返回
     *
     * @param strUrl url
     * @return 返回的api内容或html
     */
    public static byte[] GetBinary(String strUrl) {
        return GetBinary(strUrl, _defaultConfig);
    }

    /**
     * GET获取url内容，并返回
     *
     * @param strUrl url
     * @param param  GET的参数
     * @return 返回的api内容或html
     */
    public static byte[] GetBinary(String strUrl, String param) {
        Config config = InitConfig();
        config.setMethod("GET");
        config.setParam(param);
        return GetBinary(strUrl, config);
    }

    /**
     * 获取url内容，并返回
     *
     * @param strUrl url
     * @param config 请求配置
     * @return 返回的api内容或html
     */
    public static byte[] GetBinary(String strUrl, Config config) {
        return GetBinary(strUrl, config, null, 0);
    }

    // 主调方法
    private static byte[] GetBinary(String strUrl, Config config, String originUrl, int deep) {
        if (config == null)
            config = _defaultConfig;
        else if (StringUtils.isEmpty(config.getEncoding()))
            config.setEncoding("UTF-8");
        if (StringUtils.isEmpty(config.getUserAgent()))
            config.setUserAgent(USER_AGENT);

        String param = config.getParam();

        strUrl = ProcessUrl(strUrl, param);

        HttpURLConnection connection = null;
        OutputStream os = null;

        try {
            URL url = new URL(strUrl);
            connection = OpenConnection(url, config.getProxy());
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(defaultTimeout);
            connection.setReadTimeout(defaultTimeout);

            // todo: 用户名密码处理 request.Credentials = new NetworkCredential(userName, password);

            if (config.getConnectTimeout() > 0)
                connection.setConnectTimeout(config.getConnectTimeout());
            if (config.getReadTimeout() > 0)
                connection.setReadTimeout(config.getReadTimeout());

            SetHeaders(connection, config);

            // todo: 写cookie，设置proxy

            connection.connect();

            int code = connection.getResponseCode();
            if ((code == 301 || code == 302) && config.isFollowRedirect()) {
                if (deep > 10)
                    throw new Exception(originUrl + ": 重定向次数超过10次");
                String location = connection.getHeaderField("Location");
                return DoRedirect(location, strUrl, config, originUrl, deep);
            }

            // 从响应里读取Cookie，并设置到默认_cookies里
            ParseCookie(strUrl, connection.getHeaderFields().get("Set-Cookie"));

            InputStream is = GetResponseStream(connection);
            return inputStream2byte(is);
        } catch (Exception exp) {
            exp.printStackTrace();
            return null;
        } finally {
            Close(connection);
            Close(os);
        }
    }

    static byte[] inputStream2byte(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] buff = new byte[100];
        int rc;
        while ((rc = inputStream.read(buff, 0, 100)) > 0) {
            byteArrayOutputStream.write(buff, 0, rc);
        }
        return byteArrayOutputStream.toByteArray();
    }


    private static Config InitConfig() {
        Config config = new Config();
        config.setMethod("GET");
        config.setEncoding("UTF-8");
        config.setFollowRedirect(true);
        config.setCatchExp(true);

        // 这2行是测试代码，要记得注释
//        config.setShowHeader(true);
        config.setProxy("127.0.0.1:1080:socks");

        return config;
    }

    private static String ProcessUrl(String strUrl, String param) {
        //region url判断处理
        if (StringUtils.isEmpty(strUrl))
            throw new IllegalArgumentException("url can't be empty.");

        strUrl = strUrl.trim();
        if (StringUtils.indexOfIgnoreCase(strUrl, "http://") != 0 &&
                StringUtils.indexOfIgnoreCase(strUrl, "https://") != 0)
            //throw new IllegalArgumentException("url must be http protocol.");
            strUrl = "http://" + strUrl;

        // 删除网址后面的#号
        int idx = strUrl.indexOf('#');
        if (idx >= 0)
            strUrl = strUrl.substring(0, idx);

        // StringUtils.equals()
        if (StringUtils.isNotBlank(param)) {
            if (strUrl.indexOf('?') < 0)
                strUrl += "?" + param;
            else
                strUrl += "&" + param;
        }

        return strUrl;
    }

    private static HttpURLConnection OpenConnection(URL url, String proxy) throws IOException {
        HttpURLConnection ret = null;
        if (StringUtils.isNotEmpty(proxy)) {
            Proxy pr = null;
            String[] arr = proxy.split(":");
            if (arr.length == 3)
                pr = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(arr[0], Integer.parseInt(arr[1])));
            else if (arr.length == 2)
                pr = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(arr[0], Integer.parseInt(arr[1])));
            else if (arr.length == 1)
                pr = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(arr[0], 80));
            if (pr != null)
                ret = (HttpURLConnection) url.openConnection(pr);
        }
        if (ret == null)
            ret = (HttpURLConnection) url.openConnection();

        return ret;
    }

    private static void SetHeaders(HttpURLConnection connection, Config config) {
        boolean cookieSetted = false;
        Map<String, String> headers = config.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                connection.setRequestProperty(key, entry.getValue());

                if (StringUtils.equalsIgnoreCase("Cookie", key))
                    cookieSetted = true;
            }
        }
        for (Map.Entry<String, String> entry : _defaultHeader.entrySet()) {
            if (headers == null || !headers.containsKey(entry.getKey()))
                connection.setRequestProperty(entry.getKey(), entry.getValue());
        }

        if (StringUtils.isNotEmpty(config.getReferer()))
            connection.setRequestProperty("Referer", config.getReferer());
        if (StringUtils.isNotEmpty(config.getUserAgent()))
            connection.setRequestProperty("User-Agent", config.getUserAgent());

        // 设置默认Cookie
        if (!cookieSetted) {
            String cookies = CombineCookie(connection.getURL().toString());
            if (StringUtils.isNotEmpty(cookies))
                connection.setRequestProperty("Cookie", cookies);
        }
    }

    /**
     * 拼接组装当前请求要使用的Cookie
     *
     * @param url url
     * @return cookie
     */
    private static String CombineCookie(String url) {
        StringBuilder sb = new StringBuilder();
        String domain = GetDomain(url);
        boolean isSsl = StringUtils.startsWithIgnoreCase(url, "https://");

        List<String> removeKeys = new ArrayList<>();
        synchronized (_cookies) {
            for (Map.Entry<String, HttpCookie> entry : _cookies.entrySet()) {
                HttpCookie cook = entry.getValue();
                if (cook.hasExpired()) {
                    // 过期了, 收集进行删除
                    removeKeys.add(entry.getKey());
                    continue;
                }

                // 仅https可用
                if (cook.getSecure() && !isSsl)
                    continue;
                // 属于子域时
                String cookDomain = cook.getDomain();
                if (StringUtils.isEmpty(cookDomain) || StringUtils.containsIgnoreCase(domain, cookDomain))
                    sb.append(cook.getName()).append("=").append(cook.getValue()).append(";");
            }
            for (String key : removeKeys)
                _cookies.remove(key);
        }
        return sb.toString();
    }

    private static void AppendHeader(StringBuilder sb, Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            sb.append("  ");
            String key = entry.getKey();
            if (key != null) // 响应header有key为空，value为 HTTP/1.1 200 OK
                sb.append(entry.getKey()).append("=");
            sb.append(entry.getValue()).append("\r\n");
        }
        sb.append("\r\n");
    }

    private static byte[] DoRedirect(String redirectUrl, String currentUrl, Config config,
                                     String originUrl, int deep) throws CloneNotSupportedException {
        Config redirectConfig = config.clone();
        // 跳转只允许GET
        redirectConfig.setMethod("GET");
        redirectConfig.setParam("");
        redirectConfig.setReferer(currentUrl);
        // 如果跳转，递归调用. 这里的Location不区分大小写
        redirectUrl = GetRedirectUrl(redirectUrl, currentUrl);
        if (StringUtils.isNotEmpty(redirectUrl)) {
            originUrl = originUrl == null ? currentUrl : originUrl;
            return GetBinary(redirectUrl, redirectConfig, originUrl, deep + 1);
        }
        return null;
    }

    private static String GetRedirectUrl(String redirectUrl, String originUrl) {
        if (StringUtils.isEmpty(redirectUrl))
            return null;
        if (StringUtils.startsWithIgnoreCase(redirectUrl, "http://"))
            return redirectUrl;
        if (StringUtils.startsWithIgnoreCase(redirectUrl, "https://"))
            return redirectUrl;
        return GetDomain(originUrl) + redirectUrl;
    }

    private static String GetDomain(String url) {
        int idx = url.indexOf('/', 8); // 跳过https://
        if (idx > 0) {
            return url.substring(0, idx);
        }
        return url;
    }

    private static InputStream GetResponseStream(HttpURLConnection connection) throws IOException {
        InputStream is;
        int code = connection.getResponseCode();
        if (code <= 399) {
            is = connection.getInputStream();
        } else {
            is = connection.getErrorStream();
        }
        if (is == null)
            return null;

        String contentEncoding = connection.getContentEncoding();
        if (contentEncoding != null && contentEncoding.equals("gzip")) {
            is = new GZIPInputStream(is);
        }
        return is;
    }

    private static void ParseCookie(String url, List<String> arrCookies) {
        if (arrCookies == null)
            return;
        String domain = GetDomain(url);
        for (String item : arrCookies) {
            HttpCookie cookie = HttpCookie.parse(item).get(0);
            if (StringUtils.isNotEmpty(cookie.getDomain()))
                domain = cookie.getDomain();
            else
                cookie.setDomain(domain);
            String key = domain + ":" + cookie.getName();
            synchronized (_cookies) {
                if (_cookies.containsKey(key))
                    _cookies.replace(key, cookie);
                else
                    _cookies.put(key, cookie);
            }
        }
    }

    /**
     * 批量关闭对象
     *
     * @param arrObj 对象
     */
    private static void Close(Closeable... arrObj) {
        if (arrObj != null) {
            for (Closeable item : arrObj) {
                try {
                    if (item != null)
                        item.close();
                } catch (Exception exp) {
                    exp.printStackTrace();
                }
            }
        }
    }

    /**
     * 批量关闭对象
     *
     * @param arrObj 对象
     */
    private static void Close(HttpURLConnection... arrObj) {
        if (arrObj != null) {
            for (HttpURLConnection item : arrObj) {
                try {
                    if (item != null)
                        item.disconnect();
                } catch (Exception exp) {
                    exp.printStackTrace();
                }
            }
        }
    }

    /**
     * 发起请求的配置项
     */
    @Data
    public static class Config implements Cloneable {
        /**
         * 请求方法：GET/POST/PUT/DELETE
         */
        private String method;
        /**
         * 本次请求的参数
         */
        private String param;
        /**
         *
         */
        private String encoding;
        /**
         * 本次请求的来源信息
         */
        private String referer;
        /**
         * 本次请求的连接超时时长，毫秒
         */
        private int connectTimeout;
        /**
         * 本次请求读取数据的等待超时时长，毫秒
         */
        private int readTimeout;
        /**
         * 返回的请求结果，是否需要包含请求头和响应头信息
         */
        private boolean showHeader;
        /**
         * 本次请求要使用的用户名
         */
        private String userName;
        /**
         * 本次请求要使用的密码
         */
        private String password;
        /**
         * 本次请求使用的代理，如 10.1.2.3:8080
         */
        private String proxy;
        /**
         * 本次请求使用的User-Agent
         */
        private String userAgent;
        /**
         * 是否要跟随301/302跳转
         */
        private boolean followRedirect;
        /**
         * 本次请求要设置的请求头信息
         */
        private Map<String, String> headers;
        private boolean catchExp;

        public Config clone() throws CloneNotSupportedException {
            return (Config) super.clone();
        }
    }

    /**
     * 此类用于解决ssl证书信任问题，比如通过Fiddler访问https报错：
     * unable to find valid certification path to requested target
     */
    public static class X509TrustManagerExt implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }
    }

}