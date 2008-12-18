package com.thinkminimo.golf;

import java.io.File;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.BufferedReader;
import java.util.concurrent.ConcurrentHashMap;
import org.mozilla.javascript.*;

import java.net.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.mortbay.log.Log;

import com.gargoylesoftware.htmlunit.*;
import com.gargoylesoftware.htmlunit.html.*;
import com.gargoylesoftware.htmlunit.javascript.*;

/**
 * Golf servlet class!
 */
public class GolfServlet extends HttpServlet {
  
  /**
   * Each client in proxy mode has one of these stored javascript
   * virtual machines (JSVMs). They're linked to the last used golfNum
   * to provide a way of checking for stale sessions, since sessions
   * are created in a weird way after the second, and not the first
   * request.
   */
  private class StoredJSVM {
    /** sequence number, see GolfServlet.golfNum */
    public int golfNum;
    /** stored JSVM */
    public WebClient client;

    /**
     * Constructor.
     *
     * @param       client      the JSVM
     * @param       golfNum     the sequence number
     */
    StoredJSVM(WebClient client, int golfNum) {
      this.client = client;
      this.golfNum = golfNum;
    }
  }

  /**
   * Contains state info for a golf request. This is what should be passed
   * around rather than the raw request or response.
   */
  public class GolfContext {
    /**
     * Parsed request parameters. This encapsulates the request parameters
     * in case it is necessary to change the query string later.
     */
    public class GolfParams {
      /** the event to proxy in proxy mode ("click", etc.) */
      public String       event       = null;
      /** the element to fire the event on (identified by golfId) */
      public String       target      = null; 
      /** session ID */
      public String       session     = null; 
      /** golf proxy request sequence number (increments each proxied request) */
      public String       golf        = null;
      /** if set to the value of "false", client mode is disabled */
      public String       js          = null; 
      /** set to the requested callback function name for JSONP services */
      public String       jsonp       = null; 

      /**
       * Constructor.
       *
       * @param       request     the http request object
       */
      public GolfParams (HttpServletRequest request) {
        event       = request.getParameter("event");
        target      = request.getParameter("target");
        session     = request.getParameter("session");
        golf        = request.getParameter("golf");
        js          = request.getParameter("js");
        jsonp       = request.getParameter("jsonp");
      }
    }

    /** the http request object */
    public HttpServletRequest   request     = null;

    /** the http response object */
    public HttpServletResponse  response    = null;

    /** the golf proxy request sequence number */
    public int                  golfNum     = 0;
    
    /** the golf session id */
    public String               session     = null;
    
    /** FIXME which browser is the client using? FIXME */
    public BrowserVersion       browser     = BrowserVersion.FIREFOX_2;
    
    /** whether or not this is a request for a static resource */
    public boolean              isStatic    = false;

    /** whether or not this is a request for JSONP services */
    public boolean              isJSONP     = false;

    /** whether or not this is an event proxy request */
    public boolean              hasEvent    = false;

    /** whether or not client mode is disabled */
    public boolean              proxyonly   = false;

    /** the jsvm for this request */
    public WebClient            client      = null;

    /** recognized http request query string parameters */
    public GolfParams           params      = null;

    /**
     * Constructor.
     *
     * @param       request     the http request object
     * @param       response    the http response object
     */
    public GolfContext(HttpServletRequest request, HttpServletResponse response) {
      this.request     = request;
      this.response    = response;
      this.params      = new GolfParams(request);

      try {
        golfNum   = Integer.parseInt(params.golf);
      } catch (NumberFormatException e) {
        // it's okay, use the default value
      }

      session = params.session;

      if (!request.getPathInfo().endsWith("/"))
        isStatic = true;

      if (params.jsonp != null)
        isJSONP = true;

      if (params.event != null && params.target != null)
        hasEvent = true;

      if (params.js != null && params.js.equals("false"))
        proxyonly = true;
    }
  }

  /** cache the initial HTML for each entry page here */
  private ConcurrentHashMap<String, String> cachedPages =
    new ConcurrentHashMap<String, String>();

  /** htmlunit webclients for proxy-mode sessions */
  private ConcurrentHashMap<String, StoredJSVM> clients =
    new ConcurrentHashMap<String, StoredJSVM>();

  /**
   * Serve http requests!
   *
   * @param       request     the http request object
   * @param       response    the http response object
   */
  public void service(HttpServletRequest request, HttpServletResponse response)
    throws IOException, ServletException
  {
    GolfContext   context         = new GolfContext(request, response);
    PrintWriter   out             = response.getWriter();
    String        result          = null;

    logRequest(context);

    try {
      if (context.isStatic) {
        // static resource
        response.setContentType(mimeType(context));
        result = doStaticResourceGet(context);
      } else {
        // dynamic page (golf proxy stuff)
        response.setContentType("text/html");
        result = preprocess(doDynamicResourceGet(context), context, false);
      }

      // result is only null if a redirect was sent
      if (result != null)
        out.println(result);
      else
        return;
    }

    catch (Exception x) {
      // send a 500 status response
      errorPage(context, x);
    }

    finally {
      if (context.session != null) {
        // session exists => need to update stored jsvm, or store new one
        StoredJSVM stored = clients.get(context.session);

        if (stored != null)
          stored.golfNum = context.golfNum;
        else
          clients.put(context.session, new StoredJSVM(context.client, context.golfNum));
      }
        
      out.close();
    }
  }

  /**
   * Figure out the mime type based on the path.
   *
   * @param       path        the golf request context
   * @return                  the mime type
   */
  private String mimeType(GolfContext context) {
    String path = context.request.getPathInfo();

    if (context.isJSONP || path.endsWith(".js"))
      return "text/javascript";
    else if (path.endsWith(".html"))
      return "text/html";
    else if (path.endsWith(".css"))
      return "text/css";
    else
      return "text/plain";
  }

  /**
   * Do text processing of html to inject server/client specific things, etc.
   *
   * @param       page        the html page contents
   * @param       context     the golf request object
   * @param       server      whether to process for serverside or clientside
   * @return                  the processed page html contents
   */
  private String preprocess(String page, GolfContext context, boolean server) {
    // pattern that should match the wrapper links added for proxy mode
    String pat = "<a href=\"\\?event=[a-zA-Z]+&amp;target=[0-9]+&amp;golf=";

    // document type: xhtml
    String dtd = "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" " +
      "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n";

    // remove the golfid attribute as it's not necessary on the client
    page = page.replaceAll("(<[^>]+) golfid=['\"][0-9]+['\"]", "$1");

    // proxy mode only, so remove all javascript except on serverside
    if (context.proxyonly && !server)
      page = page.replaceAll("<script type=\"text/javascript\"[^>]*>[^<]*</script>", "");

    // increment the golf sequence numbers in the event proxy links
    page = page.replaceAll( "("+pat+")[0-9]+", "$1"+ context.golfNum + 
        (context.session == null ? "" : "&amp;session=" + context.session));

    // on the client window.serverside must be false, and vice versa
    page = page.replaceFirst("(window.serverside =) [a-zA-Z_]+;", 
        "$1 " + (server ? "true" : "false") + ";");

    // import the session ID into the javascript environment
    page = page.replaceFirst("(window.sessionid =) \"[a-zA-Z_]+\";", 
        (context.session == null ? "" : "$1 \"" + context.session + "\";"));

    // no dtd for serverside because it breaks the xml parser
    return (server ? "" : dtd) + page;
  }

  /**
   * Cache the initial html rendering of the page.
   *
   * @param     request   the http request object
   */
  private synchronized void cachePage(GolfContext context) throws IOException {
    String pathInfo = context.request.getPathInfo();

    if (cachedPages.get(pathInfo) == null) {
      // FIXME: probably want a cached page for each user agent

      context.client    = new WebClient(context.browser);
      HtmlPage  page    = initClient(context);
      context.client    = null;

      cachedPages.put(pathInfo, page.asXml());
    }
  }

  /**
   * Show error page.
   *
   * @param     context   the golf request context
   * @param     e         the exception
   */
  public void errorPage(GolfContext context, Exception e) {
    try {
      PrintWriter out = context.response.getWriter();

      context.response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      context.response.setContentType("text/html");

      out.println("<html><head><title>Golf Error</title></head><body>");
      out.println("<table height='100%' width='100%'>");
      out.println("<tr><td valign='middle' align='center'>");
      out.println("<table width='600px'>");
      out.println("<tr><td style='color:darkred;border:1px dashed red;" +
                  "background:#fee;padding:0.5em;font-family:monospace'>");
      out.println("<b>Golf error:</b> " + HTMLEntityEncode(e.getMessage()));
      out.println("</td></tr>");
      out.println("</table>");
      out.println("</td></tr>");
      out.println("</table>");
      out.println("</body></html>");
    } catch (Exception x) {
      x.printStackTrace();
    }
  }

  /**
   * Generate new session id.
   *
   * @param     context   the golf request context
   * @return              the session id
   */
  private String generateSessionId(GolfContext context) {
    context.request.getSession().invalidate();
    HttpSession s = context.request.getSession(true);
    return s.getId();
  }

  /**
   * Send redirect header to send client back to the app entry point.
   *
   * @param     context   the golf request context
   */
  private void redirectToBase(GolfContext context) throws IOException {
    sendRedirect(context, context.request.getRequestURI());
  }

  /**
   * Adjusts the golfId according to the calculated offset.
   *
   * @param     newXml  the actual xhtml page
   * @param     oldXml  the cached xhtml page
   * @param     target  the requested target (from cached page)
   * @return            the corresponding target on the actual page
   */
  private String shiftGolfId(String newXml, String oldXml, String target) {
    String result   = null;
    int thisGolfId  = getFirstGolfId(newXml);
    int origGolfId  = getFirstGolfId(oldXml);
    int offset      = -1;

    if (thisGolfId >= 0 && origGolfId >= 0) {
      offset = thisGolfId - origGolfId;

      try {
        result = String.valueOf(Integer.parseInt(target) + offset);
      } catch (NumberFormatException e) {
        // it's okay, do nothing
      }
    }

    Log.info("thisGolfId = ["+thisGolfId+"]");
    Log.info("origGolfId = ["+origGolfId+"]");
    Log.info("offset     = ["+offset+"]");
    Log.info("old target = ["+target+"]");
    Log.info("new target = ["+result+"]");

    return result;
  }

  /**
   * Extracts the first golfId from an xml string.
   *
   * @param     xml     the xml string to extract from
   * @return            the golfId
   */
  private int getFirstGolfId(String xml) {
    int result = -1;

    if (xml != null) {
      String toks[] = xml.split("<[^>]+ golfid=\"");
      if (toks.length > 1) {
        String tmp = toks[1].replaceAll("[^0-9].*", "");
        try {
          result = Integer.parseInt(tmp);
        } catch (NumberFormatException e) {
          // do nothing
        }
      }
    }

    return result;
  }

  /**
   * Initializes a client JSVM for proxy mode.
   *
   * @param     context     the golf request context
   * @return                the resulting page
   */
  private synchronized HtmlPage initClient(GolfContext context) throws IOException {
    HtmlPage result;

    Log.info("INITIALIZING NEW CLIENT");

    Log.info("got here");

    // write any alert() calls to the log
    context.client.setAlertHandler(new AlertHandler() {
      public void handleAlert(Page page, String message) {
        Log.info("ALERT: " + message);
      }
    });

    Log.info("got here");

    String queryString = context.request.getQueryString();
    queryString = (queryString == null ? "" : queryString);

    Log.info("got here");

    String newHtml = getGolfResourceAsString("new.html");

    Log.info("got here");

    StringWebResponse response = new StringWebResponse(
      preprocess(newHtml, context, true),
      new URL(context.request.getRequestURL().toString() + "/" + queryString)
    );

    Log.info("got here");

    result = (HtmlPage) context.client.loadWebResponseInto(
      response,
      context.client.getCurrentWindow()
    );

    Log.info("got here");

    return result;
  }

  /**
   * Fetch a dynamic resource as a String.
   *
   * @param   context       the golf context for this request
   * @return                the resource as a String or null if not found
   */
  public String doDynamicResourceGet(GolfContext context) throws Exception {

    String      pathInfo  = context.request.getPathInfo();
    String      result    = null;
    HtmlPage    page      = null;

    if (context.hasEvent) {
      StoredJSVM  jsvm      = (context.session == null) ? null : clients.get(context.session);
      
      if (jsvm != null) {
        // have a stored jsvm

        if (context.golfNum != jsvm.golfNum) {
          // if golfNums don't match then this is a stale session
          redirectToBase(context);
          return null;
        }

        context.client = jsvm.client;
        page = (HtmlPage) context.client.getCurrentWindow().getEnclosedPage();
      } else {
        // don't have a stored jsvm

        if (context.golfNum != 1) {
          // golfNum isn't 1 so we're not coming from a cached page, and
          // there is no stored jsvm, so this must be a stale session
          redirectToBase(context);
          return null;
        }

        // either there was no session id provided in the get parameters
        // or the session id was not associated with a stored jsvm, so we
        // generate a new session id in either case
        context.session   = generateSessionId(context);

        // initialize up a new stored JSVM
        context.client    = new WebClient(context.browser);
        page              = initClient(context);

        String thisPage = page.asXml();

        // adjust offset for discrepancy between cached page and current
        // golfId values, so the shifted target points to the element on
        // the new page that corresponds to the same element on the cached
        // page (if that makes any sense at all)
        context.params.target = 
          shiftGolfId(thisPage, cachedPages.get(pathInfo), context.params.target);
      }

      // fire off the event
      if (context.params.target != null) {
        HtmlElement targetElem = null;

        try {
          targetElem = page.getHtmlElementByGolfId(context.params.target);
        } catch (Exception e) {
          Log.info(fmtLogMsg(context, "CAN'T FIRE EVENT: REDIRECTING"));
          redirectToBase(context);
          return null;
        }

        if (targetElem != null)
          targetElem.fireEvent(context.params.event);
      }
    } else {
      // client requests initial page
      context.session = null;
      context.client  = null;

      if (cachedPages.get(pathInfo) == null)
        cachePage(context);

      String cached = cachedPages.get(pathInfo);

      if (cached != null)
        result = cached;
      else
        throw new Exception("cached page should exist but was not found");
    }

    context.golfNum++;

    return (result == null ? page.asXml() : result);
  }

  /**
   * Fetch a static resource as a String.
   *
   * @param   context       the golf context for this request
   * @return                the resource as a String or null if not found
   */
  public String doStaticResourceGet(GolfContext context) {
    String pathInfo = context.request.getPathInfo();
    String result = "";

    // Static content, w/o parent directories
    try {
      String path[] = pathInfo.split("//*");

      if (path.length > 0)
        result = getGolfResourceAsString(path[path.length - 1]);

      if (result.length() == 0)
        result = getGolfResourceAsString(pathInfo);
    }

    catch (Exception x) {
      // no worries
    }

    return (result.length() > 0 ? result : null);
  }

  /**
   * Retrieves a static golf resource from the system.
   * <p>
   * Static resources are served from 5 places. When a resource is requested
   * those places are searched in the following order:
   * <ol>
   *  <li>the libraries/ directory relative to approot
   *  <li>the components/ directory relative to approot
   *  <li>the screens/ directory relative to approot
   *  <li>the approot itself
   *  <li>the jarfile resources.
   * </ol>
   *
   * @param     name      the name of the resource (may or may not contain '/')
   * @return              the resource as a stream
   */
  private InputStream getGolfResourceAsStream(String name) throws IOException {
    final String[]  paths     = { "/libraries", "/components", "/screens", "" };
    InputStream     is        = null;

    // from the filesystem
    for (String path : paths) {
      try {
        String realPath = 
          getServletContext().getRealPath(path + name);
        File theFile = new File(realPath);
        if (theFile != null && theFile.exists())
          is = new FileInputStream(theFile);
      } catch (Exception x) {}

      if (is != null)
        break;
    }

    // from the jarfile resource
    if (is == null) 
      is = getClass().getClassLoader().getResourceAsStream(name);

    if (is == null)
      throw new FileNotFoundException("File not found (" + name + ")");

    return is;
  }

  /**
   * Returns a resource as a string.
   *
   * @param     name        the name of the resource
   * @return                the resource as a string
   */
  private String getGolfResourceAsString(String name) throws IOException {
    InputStream     is  = getGolfResourceAsStream(name);
    BufferedReader  sr  = new BufferedReader(new InputStreamReader(is));
    String          ret = "";

    for (String s=""; (s = sr.readLine()) != null; )
      ret = ret + s + "\n";

    return ret;
  }

  /**
   * Format a nice log message.
   *
   * @param     context     the golf context for this request
   * @param     s           the log message
   * @return                the formatted log message
   */
  private String fmtLogMsg(GolfContext context, String s) {
    String sid = context.session;
    return (sid != null ? "[" + sid.toUpperCase().replaceAll(
          "(...)(?=...)", "$1.") + "] " : "") + s;
  }

  /**
   * Logs a http servlet request.
   *
   * @param     context     the golf context for this request
   * @param     sid         the session id
   */
  private void logRequest(GolfContext context) {
    String method = context.request.getMethod();
    String scheme = context.request.getScheme();
    String server = context.request.getServerName();
    int    port   = context.request.getServerPort();
    String uri    = context.request.getRequestURI();
    String query  = context.request.getQueryString();
    String host   = context.request.getRemoteHost();
    String sid    = context.session;

    String line   = method + " " + (scheme != null ? scheme + ":" : "") +
      "//" + (server != null ? server + ":" + port : "") +
      uri + (query != null ? "?" + query : "") + " " + host;

    Log.info(fmtLogMsg(context, line));
  }

  /**
   * Sends a redirect header to the client.
   *
   * @param     context     the golf context for this request
   * @param     uri         the URI to redirect to
   */
  private void sendRedirect(GolfContext context, String uri) throws IOException {
    Log.info(fmtLogMsg(context, "redirect -> `" + uri + "'"));
    context.response.sendRedirect(uri);
  }

  /**
   * Convenience function to do html entity encoding.
   *
   * @param     s         the string to encode
   * @return              the encoded string
   */
  public static String HTMLEntityEncode(String s) {
    StringBuffer buf = new StringBuffer();
    int len = (s == null ? -1 : s.length());

    for ( int i = 0; i < len; i++ ) {
      char c = s.charAt( i );
      if ( c>='a' && c<='z' || c>='A' && c<='Z' || c>='0' && c<='9' ) {
        buf.append( c );
      } else {
        buf.append("&#" + (int)c + ";");
      }
    }

    return buf.toString();
  }
}
