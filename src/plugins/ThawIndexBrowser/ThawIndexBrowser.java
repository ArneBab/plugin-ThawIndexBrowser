/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.ThawIndexBrowser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.util.Enumeration;

import com.db4o.ObjectContainer;

import plugins.ThawIndexBrowser.nanoxml.XMLElement;
import plugins.ThawIndexBrowser.nanoxml.XMLParseException;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.DBJob;
import freenet.client.async.ClientContext;
import freenet.client.async.DatabaseDisabledException;
import freenet.clients.http.InfoboxNode;
import freenet.clients.http.PageMaker;
import freenet.clients.http.PageNode;
import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.node.fcp.FCPServer;
import freenet.node.fcp.NotAllowedException;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginHTTP;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginRealVersioned;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.FredPluginVersioned;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;
import freenet.support.io.NativeThread;

public class ThawIndexBrowser implements FredPlugin, FredPluginThreadless, FredPluginHTTP, FredPluginVersioned, FredPluginRealVersioned, FredPluginL10n {

	public static String SELF_URI = "/plugins/plugins.ThawIndexBrowser.ThawIndexBrowser/";

	private PluginRespirator pr;

	private PageMaker pm;

	private HighLevelSimpleClient client;
	
	private FCPServer fcp;

	public void runPlugin(PluginRespirator pr2) {

		Logger.error(this, "Start");

		pr = pr2;

		pm = pr.getPageMaker();

		client = pr.getHLSimpleClient();
		
		fcp = pr.getNode().clientCore.getFCPServer();

	}

	public void terminate() {
	}

	private HTMLNode createErrorBox(String title, String errmsg) {
		InfoboxNode infobox = pm.getInfobox("infobox-alert", title);
		HTMLNode errorBox = infobox.content;
		errorBox.addChild("#", errmsg);
		return infobox.outer;
	}

	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException {
		String uri = request.getParam("key");
		if ((uri.trim().length() == 0)) {
			return makeUriPage();
		}
		return makeIndexPage(uri, false);
	}

	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException {
		String pass = request.getPartAsString("formPassword", 32);
		if ((pass.length() == 0) || !pass.equals(pr.getNode().clientCore.formPassword)) {
			return makeErrorPage("Buh! Invalid form password");
		}
		String uri = request.getPartAsString("key", 1024);
		if ((uri.trim().length() == 0)) {
			return makeUriPage();
		}
		
		if (request.getPartAsString("add", 128).length() > 0) {
			String downloadkey = request.getPartAsString("uri", 1024);
			try {
				tryAddToQueue(new FreenetURI(downloadkey));
			} catch (MalformedURLException e) {
				Logger.error(this, "TODO", e); // TODO better handling, an error page?
			}
			return makeIndexPage(uri, false);
		} else {
			return makeIndexPage(uri, request.getPartAsString("addall", 128).length() > 0);
		}
	}

	/* pages */
	private String makeUriPage() {
		PageNode page = getPageNode();
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		contentNode.addChild(createUriBox());
		return pageNode.generate();
	}

	private String makeErrorPage(String error) {
		return makeErrorPage("ERROR", error);
	}

	private String makeErrorPage(String title, String error) {
		PageNode page = getPageNode();
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		contentNode.addChild(createErrorBox(title, error));
		contentNode.addChild(createUriBox());
		return pageNode.generate();
	}

	private String makeErrorPage(String title, String error, String newUri) {
		PageNode page = getPageNode();
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		HTMLNode errorBox = createErrorBox(title, error);
		errorBox.addChild("BR");
		errorBox.addChild(new HTMLNode("a", "href", SELF_URI + "?key=" + newUri, newUri));
		contentNode.addChild(errorBox);
		contentNode.addChild(createUriBox());
		return pageNode.generate();
	}

	private PageNode getPageNode() {
		return pm.getPageNode("Thaw-Index Browser", null);
	}

	private String makeIndexPage(String index, boolean add) {

		try {
			FreenetURI uri = new FreenetURI(index);
			FetchResult content = client.fetch(uri, 90000);
			String mime = content.getMimeType();
			if (!"application/x-freenet-index".equals(mime)) {
				return makeErrorPage("Wrong mime type: " + mime, "Expected mime type \"application/x-freenet-index\", but found \""
						+ mime + "\".");
			}

			// data here, parse xml

			XMLElement xmldoc = new XMLElement();

			xmldoc.parseFromReader(new InputStreamReader(content.asBucket().getInputStream()));

			// now print the result...

			return printIndexPage(uri, xmldoc, add);

		} catch (MalformedURLException e) {
			Logger.error(this, "Invalid URI: " + index, e);
			return makeErrorPage("Invalid URI: " + index, "The given freenet key is invalid");
		} catch (FetchException e) {
			Logger.error(this, "Fetch failed for: " + index, e);
			switch (e.mode) {
				case FetchException.PERMANENT_REDIRECT:
				case FetchException.TOO_MANY_PATH_COMPONENTS:
					return makeErrorPage("Fetch failed for: " + index, "(Code: " + e.mode + ") " + e.getLocalizedMessage(),
							e.newURI.toString());
				case FetchException.DATA_NOT_FOUND:
				case FetchException.ROUTE_NOT_FOUND:
				case FetchException.REJECTED_OVERLOAD:	
				case FetchException.ALL_DATA_NOT_FOUND:
					return makeErrorPage("Fetch failed for: " + index, "(Code: " + e.mode + ") " + e.getLocalizedMessage(), index);
				default:
					return makeErrorPage("Fetch failed for: " + index, "(Code: " + e.mode + ") " + e.getLocalizedMessage());
			}
		} catch (IOException e) {
			Logger.error(this, "IOError", e);
			return makeErrorPage("IOError", "IOError while processing " + index + ": " + e.getLocalizedMessage());
		} catch (XMLParseException e) {
			Logger.error(this, "DEBUG", e);
			return makeErrorPage("Parser error", "Error while processing " + index + ": " + e.getLocalizedMessage());
		} catch (Exception e) {
			Logger.error(this, "DEBUG", e);
			return makeErrorPage("Error while processing " + index + ": " + e.getLocalizedMessage());
		}
	}

	/* page utils */
	private HTMLNode createUriBox() {
		InfoboxNode infobox = pm.getInfobox("Open an Index");
		HTMLNode browseBox = infobox.outer;
		HTMLNode browseContent = infobox.content;
		// browseContent.addChild("#", "Display the top level chunk as
		// hexprint");
		HTMLNode browseForm = pr.addFormChild(browseContent, SELF_URI, "uriForm");
		browseForm.addChild("#", "Index to explore: \u00a0 ");
		browseForm.addChild("input", new String[] { "type", "name", "size" }, new String[] { "text", "key", "70" });
		browseForm.addChild("#", "\u00a0");
		browseForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "debug", "Explore!" });
		return browseBox;
	}

	private String printIndexPage(FreenetURI uri, XMLElement doc, boolean add) {
		PageNode page = getPageNode();
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		XMLElement root = doc;

		InfoboxNode title = pm.getInfobox("Index: " + uri);
		HTMLNode titleBox = title.content;
		InfoboxNode index = pm.getInfobox("Index: " + uri);
		HTMLNode indexBox = index.content;
		InfoboxNode fileInfobox = pm.getInfobox("Files:");
		HTMLNode fileBox = fileInfobox.content;

		HTMLNode table = new HTMLNode("table", "class", "requests");
		HTMLNode headerRow = table.addChild("tr", "class", "table-header");
		headerRow.addChild("th");
		headerRow.addChild("th", "Key/Name");
		headerRow.addChild("th", "Mimetype");
		headerRow.addChild("th", "Size");

		boolean hasfiles = false;

		Enumeration rootchilds = root.enumerateChildren();

		while (rootchilds.hasMoreElements()) {
			XMLElement rc = (XMLElement) rootchilds.nextElement();
			String name = rc.getName();
			if ("header".equals(name)) {
				Enumeration headerchilds = rc.enumerateChildren();
				String titel = null;
				String clientname = null;
				String date = null;
				String category = null;
				while (headerchilds.hasMoreElements()) {
					XMLElement he = (XMLElement) headerchilds.nextElement();
					String hename = he.getName();
					if ("title".equals(hename)) {
						titel = he.getContent();
					} else if ("client".equals(hename)) {
						clientname = he.getContent();
					} else if ("date".equals(hename)) {
						date = he.getContent();
					} else if ("category".equals(hename)) {
						category = he.getContent();
					} else {
						Logger
								.error(this, "Unexpected xml element '" + hename + "' at line: " + rc.getLineNr(), new Error(
										"DEBUG"));
					}
				}

				if (titel != null) {
					titleBox.addChild("#", "Titel: \u00a0 " + titel);
					titleBox.addChild("BR");
				}
				if (clientname != null) {
					titleBox.addChild("#", "Client: \u00a0 " + clientname);
					titleBox.addChild("BR");
				}
				if (date != null) {
					titleBox.addChild("#", "Date: \u00a0 " + date);
					titleBox.addChild("BR");
				}
				if (category != null) {
					titleBox.addChild("#", "Category: \u00a0 " + category);
					titleBox.addChild("BR");
				}

			} else if ("indexes".equals(name)) {
				Enumeration indexchilds = rc.enumerateChildren();
				while (indexchilds.hasMoreElements()) {
					XMLElement ie = (XMLElement) indexchilds.nextElement();
					indexBox.addChild(new HTMLNode("a", "href", SELF_URI + "?key=" + ie.getStringAttribute("key"), ie
							.getStringAttribute("key")));
					indexBox.addChild("BR");
				}

			} else if ("files".equals(name)) {
				Enumeration filechilds = rc.enumerateChildren();
				hasfiles = rc.countChildren() > 0;
				while (filechilds.hasMoreElements()) {
					XMLElement fe = (XMLElement) filechilds.nextElement();
					HTMLNode fileRow = table.addChild("tr");
					String s = fe.getStringAttribute("key");
					String s1;
					try {
						FreenetURI u = new FreenetURI(s);
						if (add) {
							tryAddToQueue(u);
						}

						if (s.length() > 100) {
							s1 = s.substring(0, 12);
							s1 += "...";
							s1 += s.substring(85);
							// //s = s1;
						} else {
							s1 = s;
						}
						fileRow.addChild(createAddCell(s, uri.toString()));
						fileRow.addChild(createCell(new HTMLNode("a", "href", "/?key=" + s, s1)));
					} catch (MalformedURLException e1) {
						fileRow.addChild(new HTMLNode("td"));
						fileRow.addChild(createCell(new HTMLNode("#", s)));
					}

					fileRow.addChild(createCell(new HTMLNode("#", fe.getStringAttribute("mime"))));
					fileRow.addChild(createCell(new HTMLNode("#", fe.getStringAttribute("size"))));

				}
				HTMLNode allRow = table.addChild("tr");
				allRow.addChild(createAddAllCell(uri.toString()));
				fileBox.addChild(table);

			} else if ("comments".equals(name)) {

			} else {
				Logger.error(this, "Unexpected xml element '" + name + "' at line: " + rc.getLineNr(), new Error("DEBUG"));
			}
		}

		contentNode.addChild(title.outer);
		contentNode.addChild(index.outer);

		if (hasfiles)
			contentNode.addChild(fileInfobox.outer);

		contentNode.addChild(createUriBox());
		return pageNode.generate();
	}
	
	private void tryAddToQueue(final FreenetURI fetchURI) {
		
		try {
			pr.getNode().clientCore.clientContext.jobRunner.queue(new DBJob() {

				public boolean run(ObjectContainer container, ClientContext context) {
					try {
						fcp.makePersistentGlobalRequest(fetchURI, false, null, "forever", "disk", false, container, pr.getNode().clientCore.getDownloadsDir());
					} catch (NotAllowedException e) {
						Logger.normal(this, "Failed to make persistent request: "+e, e);
					} catch (IOException e) {
						Logger.normal(this, "Failed to make persistent request: "+e, e);
					} catch (Throwable t) {
						// Unexpected and severe, might even be OOM, just log it.
						Logger.error(this, "Failed to make persistent request: "+t, t);
					}
					return true;
				}
				
			}, NativeThread.HIGH_PRIORITY, false);
		} catch (DatabaseDisabledException e) {
			// :(
			// We don't handle other errors well, so lets not handle this well either. :|
		}
	}

	private HTMLNode createAddCell(String key, String uri) {
		// FIXME let the user select the downloads dir, like on the Queue page.
		// We could use the same form that the queue page does.
		HTMLNode deleteNode = new HTMLNode("td");
		HTMLNode deleteForm = pr.addFormChild(deleteNode, SELF_URI, "addForm-" + key.hashCode());
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "uri", key });
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", uri });
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "add", "Download" });
		return deleteNode;
	}
	
	private HTMLNode createAddAllCell(String uri) {
		// FIXME let the user select the downloads dir, like on the Queue page.
		// We'd need to come back here, or possibly convert it to use the same structure as the download keys box. 
		HTMLNode deleteNode = new HTMLNode("td");
		HTMLNode deleteForm = pr.addFormChild(deleteNode, SELF_URI, "addForm-all");
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", uri });
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "addall", "Download all" });
		return deleteNode;
	}

	
	private HTMLNode createCell(HTMLNode node) {
		HTMLNode cell = new HTMLNode("td");
		cell.addChild(node);
		return cell;
	}

	public String getVersion() {
		return "0.1 r"+Version.getSvnRevision();
	}
	
	public long getRealVersion() {
		return Version.getVersion();
	}

	public static void main(String[] args) {
		ThawIndexBrowser tib = new ThawIndexBrowser();
		System.out.println("=====");
		System.out.println(tib.getVersion());
		System.out.println("=====");		
	}

	public String getString(String key) {
		// TODO
		return key;
	}

	public void setLanguage(LANGUAGE newLanguage) {
		// TODO 		
	}

}
