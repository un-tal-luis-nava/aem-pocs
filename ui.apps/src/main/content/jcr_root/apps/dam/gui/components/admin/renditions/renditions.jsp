<%@page session="false"
        import="com.day.cq.i18n.I18n,
    	        org.apache.commons.lang.StringUtils,
                org.apache.sling.api.resource.Resource,
                org.apache.sling.tenant.Tenant,
                javax.jcr.security.AccessControlManager,
                javax.jcr.security.Privilege,
                com.adobe.granite.ui.components.AttrBuilder,
                com.day.cq.dam.api.DamConstants,
                com.day.cq.dam.api.Asset,
                java.util.Map,
                javax.jcr.Session,
                javax.jcr.Node,
                org.apache.jackrabbit.util.Text,
                com.adobe.granite.xss.XSSAPI,
                com.day.cq.dam.commons.util.UIHelper,
                com.day.cq.dam.api.Rendition,
                com.day.cq.dam.commons.util.DamUtil,
                com.adobe.cq.dam.dm.delivery.api.ImageDelivery,
                com.adobe.cq.dam.dm.delivery.api.TenantSettings,
                java.awt.Dimension,
                com.adobe.granite.ui.components.Config"%><%
%><%@ page import="java.util.ArrayList" %><%
%><%@taglib prefix="cq" uri="http://www.day.com/taglibs/cq/1.0" %><%
%><cq:defineObjects /><cq:includeClientLib categories="dam.gui.renditions" /><%
    I18n i18n = new I18n(request);
    int maxPixHeight = 0;
    int maxPixWidth = 0;

    Tenant tenant = resourceResolver.adaptTo(Tenant.class);
    if (tenant != null && tenant.getId() == null) {
        tenant = null;
    }
    boolean isTenantUser = (tenant != null);

    ImageDelivery imageDelivery = sling.getService(ImageDelivery.class);
    TenantSettings tenantSettings = imageDelivery.getTenantSettings(isTenantUser ? tenant.getName() : "");
    if(tenantSettings != null) {
    	Dimension dimensionIS = tenantSettings.getMaxPix();
    	if(dimensionIS != null) {
    		maxPixWidth = (int)dimensionIS.getWidth();
    		maxPixHeight  = (int)dimensionIS.getHeight();
    }
    }

    String contentPath = "";
	String optionsConfig = slingRequest.getParameter("optionsConfig");
	String isResponsive = slingRequest.getParameter("isresponsive");
    String isSmartCropStr = slingRequest.getParameter("issmartcrop");
    boolean isSmartCrop = false;
    if (null != isSmartCropStr){
        if (!isSmartCropStr.isEmpty()){
            isSmartCrop = isSmartCropStr.equals("true");
        }
    }

	String localview = slingRequest.getParameter("localview");
	String origPath = slingRequest.getRequestPathInfo().getSuffix() + (localview != null ? localview : "");

	Resource currentResource = slingRequest.getResourceResolver().getResource(origPath);

    Node currentResourceNode = currentResource.adaptTo(Node.class);
    //For loading dynamic rendition, since dynamic rendition is loaded from different host.
    String dynamicRenditionPath = slingRequest.getParameter("dyn");
    String dynamicRenditionSize = "";
    String dynamicRenditionFmt = slingRequest.getParameter("dyntype");
    boolean isDynamicRendition = false;
    boolean isPDFRendition = false;
    if (dynamicRenditionPath != null) {
        contentPath = dynamicRenditionPath;
        isDynamicRendition = true;
        if (null != dynamicRenditionFmt){
            isPDFRendition = dynamicRenditionFmt.toLowerCase().equals("pdf");
        }
    } else {
        if (!isSmartCrop && !DamUtil.isRendition(currentResource)) {
            return;
        }
        contentPath = Text.escapePath(currentResource.getPath());
        contentPath = request.getContextPath() + contentPath + "?ch_ck=" + UIHelper.getCacheKiller(currentResourceNode);
    }
    AccessControlManager acm = resourceResolver.adaptTo(Session.class).getAccessControlManager();
    Config cfg = new Config(resource);
    String []excludedRenditionsFromDelete = cfg.get("excludedRenditionsFromDelete", String[].class);
    AttrBuilder attrs = new AttrBuilder(request, xssAPI);
    attrs.addClass(cfg.get("class", String.class));
    boolean videoRendition = false;
    String renditionMimeType = "";
    Rendition rendition = currentResource.adaptTo(Rendition.class);

    boolean is3Dasset = false;
    if (null != rendition){

        Asset asset = rendition.getAsset();
        if(asset != null) {
            String interactive3DAsset = asset.getMetadataValue("dam:interactive3DAsset");
            is3Dasset = interactive3DAsset != null && "true".equals(interactive3DAsset.toLowerCase()) && "original".equals(rendition.getName());
        }

        renditionMimeType = rendition.getMimeType();

        if (renditionMimeType != null 
                && renditionMimeType.contains("video")) {
            videoRendition = true;
        }
        String renditionName = rendition.getName();
        boolean canDeleteRendition = UIHelper.hasPermission(acm, currentResource, Privilege.JCR_REMOVE_CHILD_NODES);
        if (canDeleteRendition && excludedRenditionsFromDelete != null) {
            for (int i=0; i < excludedRenditionsFromDelete.length; i++) {
                if (renditionName.equals(excludedRenditionsFromDelete[i])) {
                    canDeleteRendition = false;
                    break;
                }
            }
        }
        attrs.addOther("can-delete-rendition", new Boolean(canDeleteRendition).toString());
        //Prevent NPE when dynamicRenditionFmt is null
        if (dynamicRenditionFmt == null) {
            dynamicRenditionFmt = "";
        }

        String[] fmts = dynamicRenditionFmt.split(",");
        dynamicRenditionFmt = fmts[0];
        if (!dynamicRenditionFmt.equals("jpg") && !dynamicRenditionFmt.equals("pjpeg")  && !dynamicRenditionFmt.equals("gif") && !dynamicRenditionFmt.equals("gif-alpha") && !dynamicRenditionFmt.equals("png") && !dynamicRenditionFmt.equals("png-alpha")) {
            dynamicRenditionFmt = "jpeg";
        }
        fmts[0] = dynamicRenditionFmt;
        dynamicRenditionFmt = StringUtils.join(fmts, ",");

    }

%>

<div <%= attrs.build() %>>
     <!-- add ck to contentPath -->
     <% if (videoRendition) { %>

      <video class="dam-renditions-image" width="100%" height="100%" preload="auto" controls="controls" >
      <source src="<%= xssAPI.getValidHref(contentPath) %>" type="video/ogg">

      <source src="<%= xssAPI.getValidHref(contentPath) %>" type="video/mp4">
        Granite.I18n.get("Your browser does not support the video tag.")
      </video>
     <% } else if (is3Dasset) { %>
            <cq:includeClientLib categories="dm.gui.s7dam.dimensionalpreview" />
            <cq:include script="/libs/dam/gui/components/s7dam/dimensionalpreview/dimensionalpreview.jsp"/>
     <% } else if (isDynamicRendition && !isSmartCrop) { %>
        <!-- dynamic rendition with extra wrapper to allow scrollbar for content -->
        <div class="dam-dynamic-rendition-canvas">
            <!-- At the end of url, override fmt to derived value (above) so that it correctly renders in browser -->
            <% optionsConfig = xssAPI.getValidHref((optionsConfig != null ? "$" + Text.escape(optionsConfig) + "$" : "") + "&fmt=" + dynamicRenditionFmt + "&ch_ck=" + UIHelper.getCacheKiller(currentResourceNode) + dynamicRenditionSize); %>

            <img id="remotePreview" />
            <script type="text/javascript">
                var assetPath = $("#rendition-preview").data("ips-imageurl") || $(".dm-setup-info").data("assetPath");
                var remotePreviewSrc = $(".dm-setup-info").data("imageserver") + assetPath + "?<%= optionsConfig %>";

                <% if(isResponsive != null) {%>
                    var maxW = <%= maxPixWidth %>;
                    var maxH = <%= maxPixHeight %>;
                    var tW = $("#image-preview-unif").data('assetTiffwidth');
                    var tH = $("#image-preview-unif").data('assetTiffheight');
                    var minW = Math.min(maxW,tW);
                    var minH = Math.min(maxH,tH);
                    if(tW > tH && minW) {
                        remotePreviewSrc += '&wid=' + minW;
                    } else if (minH) {
                        remotePreviewSrc += '&hei=' + minH;
                    }
                <% } %>
                $("#remotePreview").attr("src", remotePreviewSrc);
            </script>

        </div>
     <% } else if (isSmartCrop) { %>
        <cq:include script="/libs/dam/gui/components/s7dam/smartcroprenditions/smartcroprenditionspreview.jsp"/>
     <% } else if (UIHelper.canRenderOnWeb(renditionMimeType) || canRenderOnWeb(renditionMimeType)) { %>
            <img class="dam-renditions-image" src="<%= xssAPI.getValidHref(contentPath) %>"  alt="<%=xssAPI.encodeForHTMLAttr(UIHelper.getAltText(currentResource))%>"/>
     <% } else  { %>
            <p style="text-align: center"><%= i18n.get("Preview is not supported for the selected item.")%></p>
     <% } %>
</div><%!
    // Allows preview of WEBP images through their original renditions.
    private boolean canRenderOnWeb(String renditionMimeType){
        return "image/webp".equals(renditionMimeType);
    }
%>