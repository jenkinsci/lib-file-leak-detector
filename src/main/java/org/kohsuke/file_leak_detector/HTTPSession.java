package org.kohsuke.file_leak_detector;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Handles one session, i.e. parses the HTTP request
 * and returns the response.
 */
public abstract class HTTPSession {
	/**
	 * Some HTTP response status codes
	 */
	public static final String
		HTTP_OK = "200 OK",
		HTTP_REDIRECT = "301 Moved Permanently",
		HTTP_FORBIDDEN = "403 Forbidden",
		HTTP_NOTFOUND = "404 Not Found",
		HTTP_BADREQUEST = "400 Bad Request",
		HTTP_INTERNALERROR = "500 Internal Server Error",
		HTTP_NOTIMPLEMENTED = "501 Not Implemented";

	/**
	 * Common mime types for dynamic content
	 */
	public static final String
		MIME_PLAINTEXT = "text/plain",
		MIME_HTML = "text/html",
		MIME_DEFAULT_BINARY = "application/octet-stream";

	/**
	 * GMT date formatter, have a local instance to avoid multi-threading issues
	 */
    private java.text.SimpleDateFormat gmtFrmt;

	public HTTPSession( Socket s )
	{
		gmtFrmt = new java.text.SimpleDateFormat( "E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);	// NOPMD
		gmtFrmt.setTimeZone(TimeZone.getTimeZone("GMT"));

		mySocket = s;
	}

	public void run()
	{
		try
		{
			InputStream is = mySocket.getInputStream();
			if ( is == null) {
				return;
			}
			BufferedReader in = new BufferedReader( new InputStreamReader( is ));

			// Read the request line
			String inLine = in.readLine();
			try {
				if (inLine == null) {
					return;
				}

				// Ok, now do the serve()
				InputStream r = serve();
				if ( r == null ) {	// NOSONAR - server() can be overwritten and thus could return null! 
					sendError( HTTP_INTERNALERROR, "SERVER INTERNAL ERROR: Serve() returned a null response." );
				} else {
					sendResponse( HTTP_OK, MIME_HTML, r);
				}
			} catch ( InterruptedException ie ) {
				// Thrown by sendError, ignore and exit the thread.
			} catch (Throwable e) {
				System.err.println("Had Exception in HTTPSession handling thread");
				e.printStackTrace();
	
				String msg = "<html><body>Exception in HTTPSession handling thread, error: " + e.getMessage() + "</body></html>";
				try
				{
					sendError( HTTP_INTERNALERROR, msg);
				}
				catch ( Throwable t ) {} // NOPMD - imported code
			} finally {
				in.close();
			}
		}
		catch ( IOException ioe )
		{
			try
			{
				sendError( HTTP_INTERNALERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
			}
			catch ( Throwable t ) {} // NOPMD - imported code
		}
	}

	public abstract InputStream serve();

	/**
	 * Returns an error message as a HTTP response and
	 * throws InterruptedException to stop furhter request processing.
	 */
	private void sendError( String status, String msg ) throws InterruptedException
	{
		sendResponse( status, MIME_PLAINTEXT, new ByteArrayInputStream( msg.getBytes()));
		throw new InterruptedException();
	}

	/**
	 * Sends given response to the socket.
	 */
	private void sendResponse( String status, String mime, InputStream data )
	{
		try
		{
			if ( status == null )
			 {
				throw new Error( "sendResponse(): Status can't be null." ); // NOPMD - imported code
			}

			OutputStream out = mySocket.getOutputStream();
			PrintWriter pw = new PrintWriter( out );
			pw.print("HTTP/1.0 " + status + " \r\n");

			if ( mime != null ) {
				pw.print("Content-Type: " + mime + "\r\n");
			}

			pw.print( "Date: " + gmtFrmt.format( new Date()) + "\r\n");

			pw.print("\r\n");
			pw.flush();

			if ( data != null )
			{
				byte[] buff = new byte[2048];
				while (true)
				{
					int read = data.read( buff, 0, 2048 );
					if (read <= 0) {
						break;
					}
					out.write( buff, 0, read );
				}
			}
			out.flush();
			out.close();
			if ( data != null ) {
				data.close();
			}
		}
		catch( IOException ioe )
		{
			// Couldn't write? No can do.
			try { mySocket.close(); } catch( Throwable t ) {} // NOPMD - imported code
		}
	}

	private Socket mySocket;
}
