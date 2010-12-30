/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jasig.portal.url;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.portlet.PortletMode;
import javax.portlet.WindowState;
import javax.servlet.http.HttpServletRequest;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pluto.container.PortletURLProvider.TYPE;
import org.jasig.portal.IUserPreferencesManager;
import org.jasig.portal.PortalException;
import org.jasig.portal.layout.IUserLayout;
import org.jasig.portal.layout.IUserLayoutManager;
import org.jasig.portal.layout.TransientUserLayoutManagerWrapper;
import org.jasig.portal.layout.node.IUserLayoutChannelDescription;
import org.jasig.portal.layout.node.IUserLayoutNodeDescription;
import org.jasig.portal.portlet.om.IPortletDefinition;
import org.jasig.portal.portlet.om.IPortletEntity;
import org.jasig.portal.portlet.om.IPortletWindow;
import org.jasig.portal.portlet.om.IPortletWindowId;
import org.jasig.portal.portlet.registry.IPortletDefinitionRegistry;
import org.jasig.portal.portlet.registry.IPortletEntityRegistry;
import org.jasig.portal.portlet.registry.IPortletWindowRegistry;
import org.jasig.portal.portlet.rendering.IPortletRenderer;
import org.jasig.portal.security.IPerson;
import org.jasig.portal.user.IUserInstance;
import org.jasig.portal.user.IUserInstanceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UrlPathHelper;

/**
 * {@link IPortalUrlProvider} and {@link IUrlGenerator} implementation
 * that uses a consistent human readable URL format.
 * 
 * @author Eric Dalquist
 * @version $Revision$
 */
@Component("portalUrlProvider")
public class PortalUrlProviderImpl implements IPortalUrlProvider, IUrlGenerator {
    
    
    public static final String SEPARATOR = "_";
    public static final String PORTAL_PARAM_PREFIX                  = "uPf" + SEPARATOR;
    public static final String LAYOUT_PARAM_PREFIX                  = "uPl" + SEPARATOR;
    public static final String PORTLET_CONTROL_PREFIX               = "pltC" + SEPARATOR;
    public static final String PORTLET_PARAM_PREFIX                 = "pltP" + SEPARATOR;
    public static final String PORTLET_ADDITIONAL_PARAM_PREFIX		= "pltAP" + SEPARATOR;
    public static final String PORTLET_PUBLIC_RENDER_PARAM_PREFIX   = "pltG" + SEPARATOR;
    public static final String PORTLET_RESOURCE_PARAM_PREFIX = "pltR" + SEPARATOR;

    public static final String PARAM_WINDOW_STATE   = PORTLET_CONTROL_PREFIX + "s";
    public static final String PARAM_PORTLET_MODE   = PORTLET_CONTROL_PREFIX + "m";
    public static final String PARAM_TARGET_PORTLET = PORTLET_CONTROL_PREFIX + "t";
    public static final String PARAM_PORTLET_RESOURCE_ID   = PORTLET_CONTROL_PREFIX + "resId";
    public static final String PARAM_PORTLET_CACHEABILITY   = PORTLET_CONTROL_PREFIX + "cache";
    
    public static final String SLASH = "/";
    public static final char PORTLET_PATH_ELEMENT_SEPERATOR = '.';
    public static final String PORTLET_PATH_PREFIX = "p";
    public static final String FOLDER_PATH_PREFIX = "f";
    public static final String REQUEST_TYPE_SUFFIX = ".uP";
    
    private static final Pattern URL_PARAM_NAME_PATTERN = Pattern.compile("&([^&?=\n]*)");
    private static final Pattern SLASH_PATTERN = Pattern.compile(SLASH);
    private static final String PORTAL_REQUEST_INFO_ATTR = PortalUrlProviderImpl.class.getName() + ".PORTAL_REQUEST_INFO"; 
    private static final String PORTAL_REQUEST_PARSING_IN_PROGRESS_ATTR = PortalUrlProviderImpl.class.getName() + ".PORTAL_REQUEST_PARSING_IN_PROGRESS"; 
    
    protected final Log logger = LogFactory.getLog(this.getClass());
    
    private final UrlPathHelper urlPathHelper = new UrlPathHelper();
    private String defaultEncoding = "UTF-8";
    private IUserInstanceManager userInstanceManager;
    private IPortletDefinitionRegistry portletDefinitionRegistry;
    private IPortletEntityRegistry portletEntityRegistry;
    private IPortletWindowRegistry portletWindowRegistry;
    private IPortalRequestUtils portalRequestUtils;

    /**
     * @param defaultEncoding the defaultEncoding to set
     */
    public void setDefaultEncoding(String defaultEncoding) {
        this.defaultEncoding = defaultEncoding;
    }

    /**
     * @param userInstanceManager the userInstanceManager to set
     */
    @Autowired
    public void setUserInstanceManager(IUserInstanceManager userInstanceManager) {
        this.userInstanceManager = userInstanceManager;
    }

    /**
     * @param portletDefinitionRegistry the portletDefinitionRegistry to set
     */
    @Autowired
    public void setPortletDefinitionRegistry(IPortletDefinitionRegistry portletDefinitionRegistry) {
        this.portletDefinitionRegistry = portletDefinitionRegistry;
    }

    /**
     * @param portletEntityRegistry the portletEntityRegistry to set
     */
    @Autowired
    public void setPortletEntityRegistry(IPortletEntityRegistry portletEntityRegistry) {
        this.portletEntityRegistry = portletEntityRegistry;
    }

    /**
     * @param portletWindowRegistry the portletWindowRegistry to set
     */
    @Autowired
    public void setPortletWindowRegistry(IPortletWindowRegistry portletWindowRegistry) {
        this.portletWindowRegistry = portletWindowRegistry;
    }
    
    /**
	 * @param portalRequestUtils the portalRequestUtils to set
	 */
    @Autowired
	public void setPortalRequestUtils(IPortalRequestUtils portalRequestUtils) {
		this.portalRequestUtils = portalRequestUtils;
	}
    
    private enum ParseStep {
        FOLDER,
        PORTLET,
        STATE,
        TYPE,
        COMPLETE;
    }
	
	/* (non-Javadoc)
     * @see org.jasig.portal.url.IPortalUrlProvider#getPortalRequestInfo(javax.servlet.http.HttpServletRequest)
     */
    @Override
    public IPortalRequestInfo getPortalRequestInfo(HttpServletRequest request) throws InvalidPortalRequestException {
        request = this.portalRequestUtils.getOriginalPortalRequest(request);
        final IPortalRequestInfo portalRequestInfo = (IPortalRequestInfo)request.getAttribute(PORTAL_REQUEST_INFO_ATTR);
        if (portalRequestInfo != null) {
            if(logger.isDebugEnabled()) {
                logger.debug("short-circuit: found portalRequestInfo within request attributes");
            }
            return portalRequestInfo;
        }
        // set a flag to say this request is currently being parsed
        String inProgressAttr = (String) request.getAttribute(PORTAL_REQUEST_PARSING_IN_PROGRESS_ATTR);
        if(Boolean.parseBoolean(inProgressAttr)) {
        	if(logger.isDebugEnabled()) {
        		logger.debug("short-circuit: request parsing already in progress");
        	}
        	return null;
        }
        request.setAttribute(PORTAL_REQUEST_PARSING_IN_PROGRESS_ATTR, "true");
        
        try {
        final PortalRequestInfoImpl requestInfoBuilder = new PortalRequestInfoImpl();
        PortletRequestInfoImpl portletRequestInfoBuilder = null;
        
        final Map<String, String[]> parameterMap = request.getParameterMap();
        
        final String requestPath = this.urlPathHelper.getPathWithinApplication(request);
        final String[] requestPathParts = SLASH_PATTERN.split(requestPath);
        
        UrlState requestedUrlState = null;
        ParseStep parseStep = ParseStep.FOLDER;
        for (int pathPartIndex = 0; pathPartIndex < requestPathParts.length; pathPartIndex++) {
            String pathPart = requestPathParts[pathPartIndex];
            if (StringUtils.isEmpty(pathPart)) {
                continue;
            }
            
            switch (parseStep) {
                case FOLDER: {
                    parseStep = ParseStep.PORTLET;
                    
                    if (FOLDER_PATH_PREFIX.equals(pathPart)) {
                        //Skip adding the prefix to the folders deque
                        pathPartIndex++;
                        
                        final Deque<String> folders = new LinkedList<String>();
                        for (;pathPartIndex < requestPathParts.length; pathPartIndex++) {
                            pathPart = requestPathParts[pathPartIndex];
                            
                            //Found the portlet part of the path, step back one and finish folder parsing
                            if (PORTLET_PATH_PREFIX.equals(pathPart)) {
                                pathPartIndex--;
                                break;
                            }
                            //Found the end of the path, step back one, check for state and finish folder parsing
                            else if (pathPart.endsWith(REQUEST_TYPE_SUFFIX)) {
                                pathPartIndex--;
                                pathPart = requestPathParts[pathPartIndex];
                                
                                //If a state was added to the folder list remove it and step back one so other code can handle it
                                if (UrlState.valueOfIngoreCase(pathPart, null) != null) {
                                    folders.removeLast();
                                    pathPartIndex--;
                                }
                                break;
                            }

                            folders.add(pathPart);
                        }
                        
                        //TODO resolve folder names to layout nodes and only return a targeted node id if it exists in the layout
                        if (folders.size() > 0) {
                            final String lastPart = folders.getLast();
                            requestInfoBuilder.setTargetedLayoutNodeId(lastPart);
                        }
                        break;
                    }
                }
                case PORTLET: {
                    parseStep = ParseStep.STATE;
                    
                    if (PORTLET_PATH_PREFIX.equals(pathPart)) {
                        if (++pathPartIndex < requestPathParts.length) {
                            pathPart = requestPathParts[pathPartIndex];
                            
                            portletRequestInfoBuilder = this.parseTargetPortlet(request, pathPart);
                        }
                        
                        break;
                    }
                    
                    //See if a portlet was targeted by parameter  
                    final String[] targetedPortletIds = parameterMap.get(PARAM_TARGET_PORTLET);
                    if (targetedPortletIds != null && targetedPortletIds.length > 0) {
                        final String targetedPortletString = targetedPortletIds[0];
                        portletRequestInfoBuilder = this.parseTargetPortlet(request, targetedPortletString);
                    }
                }
                case STATE: {
                    parseStep = ParseStep.TYPE;
                    
                    //States other than the default only make sense if a portlet is being targeted
                    if (portletRequestInfoBuilder == null) {
                        break;
                    }
                    
                    requestedUrlState = UrlState.valueOfIngoreCase(pathPart, null);

                    //Set the URL state
                    if (requestedUrlState != null) {
                        requestInfoBuilder.setUrlState(requestedUrlState);
                        break;
                    }
                }
                case TYPE: {
                    parseStep = ParseStep.COMPLETE;
                    
                    if (pathPartIndex == requestPathParts.length - 1 && pathPart.endsWith(REQUEST_TYPE_SUFFIX) && pathPart.length() > REQUEST_TYPE_SUFFIX.length()) {
                        final String urlTypePart = pathPart.substring(0, pathPart.length() - REQUEST_TYPE_SUFFIX.length());
                        
                        final UrlType urlType = UrlType.valueOfIngoreCase(urlTypePart, null);
                        if (urlType != null) {
                            requestInfoBuilder.setUrlType(urlType);
                            break;
                        }
                    }
                }
            }
        }
        
        
        final Map<String, String[]> portalParameters = new ParameterMap();
        final Map<String, String[]> portletParameters = new ParameterMap();
        final Map<String, String[]> portletPublicParameters = new ParameterMap();
        final Map<String, String[]> layoutParameters = new ParameterMap();
        final Map<String, String[]> resourceParameters = new ParameterMap(); 
        final Map<IPortletWindowId, ParameterMap> additionalPortletParameters = new HashMap<IPortletWindowId, ParameterMap>();
        
        for (final Map.Entry<String, String[]> parameterEntry : parameterMap.entrySet()) {
            final String name = parameterEntry.getKey();
            if (name.startsWith(PORTAL_PARAM_PREFIX)) {
                portalParameters.put(this.getParameterName(PORTAL_PARAM_PREFIX, name), parameterEntry.getValue());
            }
            else if (name.startsWith(LAYOUT_PARAM_PREFIX)) {
                layoutParameters.put(this.getParameterName(LAYOUT_PARAM_PREFIX, name), parameterEntry.getValue());
            }
            else if (name.startsWith(PORTLET_PARAM_PREFIX)) {
                portletParameters.put(this.getParameterName(PORTLET_PARAM_PREFIX, name), parameterEntry.getValue());
            }
            else if (name.startsWith(PORTLET_PUBLIC_RENDER_PARAM_PREFIX)) {
                portletPublicParameters.put(this.getParameterName(PORTLET_PUBLIC_RENDER_PARAM_PREFIX, name), parameterEntry.getValue());
            } 
            else if (name.startsWith(PORTLET_RESOURCE_PARAM_PREFIX)) {
            	resourceParameters.put(this.getParameterName(PORTLET_RESOURCE_PARAM_PREFIX, name), parameterEntry.getValue());
            } 
            else if (name.startsWith(PORTLET_ADDITIONAL_PARAM_PREFIX)) {
            	String parameterName = this.getParameterName(PORTLET_ADDITIONAL_PARAM_PREFIX, name);
            	if(parameterName.contains(SEPARATOR)) {
            		String [] parameterSplit = parameterName.split(SEPARATOR);
            		
            		IPortletWindowId portletWindowId = this.portletWindowRegistry.getPortletWindowId(parameterSplit[0]);
            		if(portletWindowId != null) {
            			ParameterMap pMap = additionalPortletParameters.get(portletWindowId);
            			if(pMap == null) {
            				pMap = new ParameterMap();
            				additionalPortletParameters.put(portletWindowId, pMap);
            			}
            		
            			String actualParameterName = parameterSplit[1];
            			pMap.put(actualParameterName, parameterEntry.getValue());	
            		}
            	}
            }
        }
        
        final Map<String, String[]> postParameters = this.getPostParameters(request);
        
        //If the request targets a portlet do more parsing
        if (portletRequestInfoBuilder != null) {
            portletParameters.putAll(postParameters);
            
            portletRequestInfoBuilder.setPortletParameters(Collections.unmodifiableMap(ParameterMap.convertArrayMap(portletParameters)));
            portletRequestInfoBuilder.setPublicPortletParameters(Collections.unmodifiableMap(ParameterMap.convertArrayMap(portletPublicParameters)));
            
            final String portletModeName = request.getParameter(PARAM_PORTLET_MODE);
            if (portletModeName != null) {
                portletRequestInfoBuilder.setPortletMode(new PortletMode(portletModeName));
            }
            
            if(UrlType.RESOURCE.equals(requestInfoBuilder.getUrlType())) {
            	// only set resourceId and cacheability if the UrlType has been set to RESOURCE
            	final String portletResourceId = request.getParameter(PARAM_PORTLET_RESOURCE_ID);
            	if(portletResourceId != null) {
            		portletRequestInfoBuilder.setResourceId(portletResourceId);
            	}
            
            	final String portletCacheability = request.getParameter(PARAM_PORTLET_CACHEABILITY);
            	if(portletCacheability != null) {
            		portletRequestInfoBuilder.setCacheability(portletCacheability);
            	}
            	
            	portletRequestInfoBuilder.setResourceParameters(Collections.unmodifiableMap(ParameterMap.convertArrayMap(resourceParameters)));
            }
            //If a portlet is targeted but no layout node is targeted must be maximized
            if (requestInfoBuilder.getTargetedLayoutNodeId() == null && (requestedUrlState == null || requestedUrlState == UrlState.NORMAL)) {
                requestInfoBuilder.setUrlState(UrlState.MAX);
            }
            
            //Set window state based on URL State first then look for the window state parameter
            final UrlState urlState = requestInfoBuilder.getUrlState();
            switch (urlState) {
                case MAX: {
                    portletRequestInfoBuilder.setWindowState(WindowState.MAXIMIZED);
                }
                break;

                case DETACHED: {
                    portletRequestInfoBuilder.setWindowState(IPortletRenderer.DETACHED);
                }
                break;

                case EXCLUSIVE: {
                    portletRequestInfoBuilder.setWindowState(IPortletRenderer.EXCLUSIVE);
                }
                break;
                
                default: {
                    final String windowStateName = request.getParameter(PARAM_WINDOW_STATE);
                    if (windowStateName != null) {
                        portletRequestInfoBuilder.setWindowState(new WindowState(windowStateName));
                    }
                }
            }
            
            //TODO delegation parsing
//            portletRequestInfoBuilder.setDelegatePortletRequestInfo(portletRequestInfoBuilder)
            
            requestInfoBuilder.setPortletRequestInfo(portletRequestInfoBuilder);
        }
        else if (requestInfoBuilder.getTargetedLayoutNodeId() != null) {
            layoutParameters.putAll(postParameters);
        }
        else {
            portalParameters.putAll(postParameters);
        }
        
        requestInfoBuilder.setLayoutParameters(Collections.unmodifiableMap(ParameterMap.convertArrayMap(layoutParameters)));
        requestInfoBuilder.setPortalParameters(Collections.unmodifiableMap(ParameterMap.convertArrayMap(portalParameters)));
        
        for(Map.Entry<IPortletWindowId, ParameterMap> mapEntry : additionalPortletParameters.entrySet()) {
        	IPortletWindowId childPortletWindowId = mapEntry.getKey();
        	Map<String, List<String>> childPortletParameters = ParameterMap.convertArrayMap(mapEntry.getValue());
        	IPortletRequestInfo childClone = portletRequestInfoBuilder.constructChildClone(childPortletWindowId, childPortletParameters);
        	
        	requestInfoBuilder.addChildAdditionalPortletRequestInfo(childPortletWindowId, childClone);
        }
        
        //Generate the canonical URL string
        final String urlString = generateUrlString(request, requestInfoBuilder);
        requestInfoBuilder.setUrlString(urlString);
        
        request.setAttribute(PORTAL_REQUEST_INFO_ATTR, requestInfoBuilder);
        
        if(logger.isDebugEnabled()) {
            logger.debug("finished building requestInfo: " + requestInfoBuilder);
        }
        
        return requestInfoBuilder;
        }finally {
        	request.removeAttribute(PORTAL_REQUEST_PARSING_IN_PROGRESS_ATTR);
        }
    }

    /**
     * 
     * @param request
     * @param portalRequestInfo
     * @return
     */
    protected String generateUrlString(HttpServletRequest request, IPortalRequestInfo portalRequestInfo) {
        final IPortletRequestInfo portletRequestInfo = portalRequestInfo.getPortletRequestInfo();
        final String targetedLayoutNodeId = portalRequestInfo.getTargetedLayoutNodeId();
        
        IBasePortalUrl basePortalUrl = null;
        
        try {
            if (portletRequestInfo != null) {
                final UrlType urlType = portalRequestInfo.getUrlType();
                final IPortletWindowId targetWindowId = portletRequestInfo.getTargetWindowId();
                final IPortletPortalUrl portletPortalUrl = this.getPortletUrl(urlType.getPortletUrlType(), request, targetWindowId);
                basePortalUrl = portletPortalUrl;
                
                final PortletMode portletMode = portletRequestInfo.getPortletMode();
                if (portletMode != null) {
                    portletPortalUrl.setPortletMode(portletMode);
                }
                final WindowState windowState = portletRequestInfo.getWindowState();
                if (windowState != null) {
                    portletPortalUrl.setWindowState(windowState);
                }
                final String resourceId = portletRequestInfo.getResourceId();
                if(resourceId != null) {
                	portletPortalUrl.setResourceID(resourceId);
                }
                final String cacheability = portletRequestInfo.getCacheability();
                if(cacheability != null) {
                	portletPortalUrl.setCacheability(cacheability);
                }
                final Map<String, List<String>> publicPortletParameters = portletRequestInfo.getPublicPortletParameters();
                if (publicPortletParameters != null) {
                    portletPortalUrl.getPublicRenderParameters().putAll(ParameterMap.convertListMap(publicPortletParameters));
                }
                final Map<String, List<String>> portletParameters = portletRequestInfo.getPortletParameters();
                if (portletParameters != null) {
                    portletPortalUrl.getPortletParameters().putAll(ParameterMap.convertListMap(portletParameters));
                }
                final IPortletRequestInfo delegatePortletRequestInfo = portletRequestInfo.getDelegatePortletRequestInfo();
                if (delegatePortletRequestInfo != null) {
                    //TODO handle delegate URL generation
                }   
            }
        }
        catch (PortalException e) {
            //TODO really need a better exception type here
            //Failed to generate layout URL (generally due to invalid portletWindowId in this request scope)
        }
        
        try {
            if (basePortalUrl == null && targetedLayoutNodeId != null) {
                final ILayoutPortalUrl layoutPortalUrl = this.getFolderUrlByNodeId(request, targetedLayoutNodeId);
                
                basePortalUrl = layoutPortalUrl;
                
                final Map<String, List<String>> layoutParameters = portalRequestInfo.getLayoutParameters();
                if (layoutParameters != null) {
                    layoutPortalUrl.setLayoutParameters(layoutParameters);
                }
                
                final boolean action = UrlType.ACTION == portalRequestInfo.getUrlType();
                layoutPortalUrl.setAction(action);
            }
        }
        catch (PortalException e) {
            //TODO really need a better exception type here
            //Failed to generate layout URL (generally due to invalid nodeId in this request scope)
        }
        
        if (basePortalUrl == null) {
            basePortalUrl = this.getDefaultUrl(request);
        }
        
        final Map<String, List<String>> portalParameters = portalRequestInfo.getPortalParameters();
        if (portalParameters != null) {
            basePortalUrl.setPortalParameters(portalParameters);
        }
        
        //TODO - are portalRequestInfo#getAdditionalPortletRequestInfo parameters portal parameters?
        return basePortalUrl.getUrlString();
    }
    
    protected String getParameterName(String prefix, String fullName) {
        if (prefix.length() >= fullName.length()) {
            return "";
        }
        
        return fullName.substring(prefix.length());
    }

    protected PortletRequestInfoImpl parseTargetPortlet(HttpServletRequest request, String targetedPortletString) {
        PortletRequestInfoImpl portletRequestInfoBuilder;
        final IUserInstance userInstance = this.userInstanceManager.getUserInstance(request);
        
        final String fname;
        final IPortletEntity portletEntity;
        
        final int seperatorIndex = targetedPortletString.indexOf(PORTLET_PATH_ELEMENT_SEPERATOR);
        if (seperatorIndex <= 0 || seperatorIndex + 1 == targetedPortletString.length()) {
            fname = targetedPortletString;
            portletEntity = this.portletEntityRegistry.getOrCreatePortletEntityByFname(userInstance, fname);
        }
        else {
            fname = targetedPortletString.substring(0, seperatorIndex);
            final String subscribeId = targetedPortletString.substring(seperatorIndex + 1);
            
            portletEntity = this.portletEntityRegistry.getOrCreatePortletEntityByFname(userInstance, fname, subscribeId);
        }
        
        
        final IPortletWindow portletWindow = this.portletWindowRegistry.getOrCreateDefaultPortletWindow(request, portletEntity.getPortletEntityId());
        
        portletRequestInfoBuilder = new PortletRequestInfoImpl(portletWindow.getPortletWindowId());
        return portletRequestInfoBuilder;
    }
    

    /**
     * Parses the request URL to return a Set of the parameter names that were part of the POST and not on the URL string.
     * 
     * @param request The request to look at.
     * @return The Set of parameter names from the POST, null if the request was not a POST.
     */
    protected Map<String, String[]> getPostParameters(HttpServletRequest request) {
        // Only posts can have parameters not in the URL, ignore non-post requests.
        final String method = request.getMethod();
        if (!"POST".equals(method)) {
            return Collections.emptyMap();
        }
        
        final Map<String, String[]> postParameterMap = new ParameterMap(request.getParameterMap());
        
        final String queryString = request.getQueryString();
        final Matcher paramNameMatcher = URL_PARAM_NAME_PATTERN.matcher("&" + queryString);

        final String encoding = this.getEncoding(request);
        
        while (paramNameMatcher.find()) {
            final String paramName = paramNameMatcher.group(1);
            String decParamName;
            try {
                decParamName = URLDecoder.decode(paramName, encoding);
            }
            catch (UnsupportedEncodingException uee) {
                decParamName = paramName;
            }
            
            postParameterMap.remove(decParamName);
        }
        
        return postParameterMap;
    }

    /* (non-Javadoc)
     * @see org.jasig.portal.url.IPortalUrlProvider#getDefaultUrl(javax.servlet.http.HttpServletRequest)
     */
    @Override
    public IBasePortalUrl getDefaultUrl(HttpServletRequest request) {
        final IUserInstance userInstance = this.userInstanceManager.getUserInstance(request);
        final IUserPreferencesManager preferencesManager = userInstance.getPreferencesManager();
        final IUserLayoutManager userLayoutManager = preferencesManager.getUserLayoutManager();

        final IUserLayout userLayout = userLayoutManager.getUserLayout();
        
        final XPathExpression defaultTabIdExpression = this.getUserDefaultTabIdExpression();
        final String defaultTabId = userLayout.findNodeId(defaultTabIdExpression);
          
        // call out to getFolderUrlByNodeId, pass in default nodeId for user
        return getFolderUrlByNodeId(request, defaultTabId);
    }

    /* (non-Javadoc)
     * @see org.jasig.portal.url.IPortalUrlProvider#getFolderUrlByNodeId(javax.servlet.http.HttpServletRequest, java.lang.String)
     */
    @Override
    public ILayoutPortalUrl getFolderUrlByNodeId(HttpServletRequest request, String folderNodeId) {
        final String resolvedFolderId = this.verifyLayoutNodeId(request, folderNodeId);
        return new LayoutPortalUrlImpl(request, this, resolvedFolderId);

    }
    
    /* (non-Javadoc)
     * @see org.jasig.portal.url.IPortalUrlProvider#getPortletResourceUrl(javax.servlet.http.HttpServletRequest, org.jasig.portal.portlet.om.IPortletWindowId)
     */
    @Override
    public IPortletResourcePortalUrl getPortletResourceUrl(HttpServletRequest request, IPortletWindowId portletWindowId) {
        final IPortletWindow portletWindow = this.portletWindowRegistry.getPortletWindow(request, portletWindowId);
        ResourceUrlProviderImpl resourcePortalUrl = new ResourceUrlProviderImpl(portletWindow, request);
        return resourcePortalUrl;
    }

    /* (non-Javadoc)
     * @see org.jasig.portal.url.IPortalUrlProvider#getPortletUrlByNodeId(javax.servlet.http.HttpServletRequest, org.jasig.portal.portlet.om.IPortletWindowId)
     */
    @Override
    public IPortletPortalUrl getPortletUrl(TYPE type, HttpServletRequest request, IPortletWindowId portletWindowId) {
        final IPortletWindow portletWindow = this.portletWindowRegistry.getPortletWindow(request, portletWindowId);
        return new PortletPortalUrlImpl(type, portletWindow, request, this);
    }

    /* (non-Javadoc)
     * @see org.jasig.portal.url.IPortalUrlProvider#getPortletUrlByFName(javax.servlet.http.HttpServletRequest, java.lang.String)
     */
    @Override
    public IPortletPortalUrl getPortletUrlByFName(TYPE type, HttpServletRequest request, String portletFName) {
        //Get the user's layout manager
        final IUserInstance userInstance = this.userInstanceManager.getUserInstance(request);
        final IUserPreferencesManager preferencesManager = userInstance.getPreferencesManager();
        final IUserLayoutManager userLayoutManager = preferencesManager.getUserLayoutManager();
        
        //Determine the subscribe ID
        final String channelSubscribeId = userLayoutManager.getSubscribeId(portletFName);
        if (channelSubscribeId == null) {
            throw new IllegalArgumentException("No channel subscribe ID found for fname '" + portletFName + "'.");
        }
        
        return this.getPortletUrlByNodeId(type, request, channelSubscribeId);
    }

    /* (non-Javadoc)
     * @see org.jasig.portal.url.IPortalUrlProvider#getPortletUrlByNodeId(javax.servlet.http.HttpServletRequest, java.lang.String)
     */
    @Override
    public IPortletPortalUrl getPortletUrlByNodeId(TYPE type, HttpServletRequest request, String portletNodeId) {
        final IUserInstance userInstance = this.userInstanceManager.getUserInstance(request);
        final IUserPreferencesManager preferencesManager = userInstance.getPreferencesManager();
        final IUserLayoutManager userLayoutManager = preferencesManager.getUserLayoutManager();
        
        //Find the channel and portlet definitions
        final IUserLayoutChannelDescription channelNode = (IUserLayoutChannelDescription)userLayoutManager.getNode(portletNodeId);
        final String channelPublishId = channelNode.getChannelPublishId();

        final IPortletDefinition portletDefinition = portletDefinitionRegistry.getPortletDefinition(channelPublishId);
        if (portletDefinition == null) {
            throw new IllegalArgumentException("No portlet defintion found for channel definition '" + channelPublishId + "'.");
        }
        
        //Determine the appropriate portlet window ID
        final IPerson person = userInstance.getPerson();
        final IPortletEntity portletEntity = this.portletEntityRegistry.getOrCreatePortletEntity(portletDefinition.getPortletDefinitionId(), portletNodeId, person.getID());
        final IPortletWindow portletWindow = this.portletWindowRegistry.getOrCreateDefaultPortletWindow(request, portletEntity.getPortletEntityId());
        
        return this.getPortletUrl(type, request, portletWindow.getPortletWindowId());
    }
    
    /* (non-Javadoc)
     * @see org.jasig.portal.url.IUrlGenerator#generateLayoutUrl(javax.servlet.http.HttpServletRequest, org.jasig.portal.url.ILayoutPortalUrl)
     */
    @Override
    public String generateLayoutUrl(HttpServletRequest request, ILayoutPortalUrl layoutPortalUrl) {
        final String encoding = this.getEncoding(request);
        final UrlBuilder url = new UrlBuilder(encoding);
        
        final String contextPath = this.getCleanedContextPath(request);
        url.setPath(contextPath);

        final String targetFolderId = layoutPortalUrl.getTargetFolderId();
        final String folderId = this.verifyLayoutNodeId(request, targetFolderId);
        url.addPath(FOLDER_PATH_PREFIX);
        url.addPath(folderId);

        url.addPath(UrlState.NORMAL.toLowercaseString());
        
        final boolean action = layoutPortalUrl.isAction();
        if (action) {
            url.addPath(UrlType.ACTION.toLowercaseString() + REQUEST_TYPE_SUFFIX);
        }
        else {
            url.addPath(UrlType.RENDER.toLowercaseString() + REQUEST_TYPE_SUFFIX);
        }

        //Add all portal parameters
        final Map<String, List<String>> portalParameters = layoutPortalUrl.getPortalParameters();
        url.addParameters(PORTAL_PARAM_PREFIX, portalParameters);
        
        //Add all layout parameters
        final Map<String, List<String>> layoutParameters = layoutPortalUrl.getLayoutParameters();
        url.addParameters(LAYOUT_PARAM_PREFIX, layoutParameters);
        
        if(logger.isDebugEnabled()) {
            logger.debug("Generated '" + url + "' from '" + layoutPortalUrl);
        }
        return url.toString();
    }

    /* (non-Javadoc)
     * @see org.jasig.portal.url.IUrlGenerator#generatePortletUrl(javax.servlet.http.HttpServletRequest, org.jasig.portal.url.IPortletPortalUrl)
     */
    @Override
    public String generatePortletUrl(HttpServletRequest request, IPortletPortalUrl portletPortalUrl) {
        Validate.notNull(request, "HttpServletRequest was null");
        Validate.notNull(portletPortalUrl, "IPortalPortletUrl was null");
       
        //Convert the callback request to the portal request
        request = this.portalRequestUtils.getOriginalPortalRequest(request);
        
        final String encoding = this.getEncoding(request);
        final UrlBuilder url = new UrlBuilder(encoding);
        
        final String contextPath = this.getCleanedContextPath(request);
        url.setPath(contextPath);
        
        final IPortletWindowId portletWindowId = portletPortalUrl.getTargetWindowId();
        final IPortletWindow portletWindow = this.portletWindowRegistry.getPortletWindow(request, portletWindowId);
        final IPortletEntity portletEntity = this.portletEntityRegistry.getPortletEntity(portletWindow.getPortletEntityId());
        
        //Add folder information if available: /tabId
        final String channelSubscribeId = portletEntity.getChannelSubscribeId();
        // if not a transient node, we need to lookup user layout information
        if (!channelSubscribeId.startsWith(TransientUserLayoutManagerWrapper.SUBSCRIBE_PREFIX)) {
            final IUserInstance userInstance = this.userInstanceManager.getUserInstance(request);
            final IUserPreferencesManager preferencesManager = userInstance.getPreferencesManager();
            final IUserLayoutManager userLayoutManager = preferencesManager.getUserLayoutManager();
            final IUserLayout userLayout = userLayoutManager.getUserLayout();
            
            final XPathExpression findChannelTabExpression = this.getFindChannelTabIdExpression(channelSubscribeId);
            final String tabId = userLayout.findNodeId(findChannelTabExpression);
            
            //Add folder ID of parent tab if it exists
            if (tabId != null) {
                final String folderId = this.verifyLayoutNodeId(request, tabId);
                url.addPath(FOLDER_PATH_PREFIX);
                url.addPath(folderId);
            }
        }
        
        //Add state information
        final WindowState requestedWindowState = portletPortalUrl.getWindowState();
        final WindowState currentWindowState = portletWindow.getWindowState();
        final WindowState urlWindowState = requestedWindowState != null ? requestedWindowState : currentWindowState;
      
        final UrlState urlState;
        if (WindowState.MAXIMIZED.equals(urlWindowState)) {
            urlState = UrlState.MAX;
        }
        else if (IPortletRenderer.DETACHED.equals(urlWindowState)) {
            urlState = UrlState.DETACHED;
        }
        else if (IPortletRenderer.EXCLUSIVE.equals(urlWindowState)) {
            urlState = UrlState.EXCLUSIVE;
        }
        else {
            if (!WindowState.NORMAL.equals(urlWindowState) &&
                    !WindowState.MINIMIZED.equals(urlWindowState)) {
                this.logger.warn("Unknown WindowState '" + urlWindowState + "' specified for portlet window " + portletWindow + ", defaulting to NORMAL");
            }
            
            urlState = UrlState.NORMAL;
        }
        
        final IPortletDefinition portletDefinition = this.portletDefinitionRegistry.getPortletDefinition(portletEntity.getPortletDefinitionId());
        final String fname = portletDefinition.getFName();
        final String targetedPortletString = fname + PORTLET_PATH_ELEMENT_SEPERATOR + channelSubscribeId;
        
        //Add targeted portlet information if rendering in a single-portlet state: /fname.chanid
        if (UrlState.NORMAL != urlState) {
            url.addPath(PORTLET_PATH_PREFIX);
            url.addPath(targetedPortletString);
        }
        //Otherwise include the targeted portlet info as a parameter
        else {
            url.addParameter(PARAM_TARGET_PORTLET, targetedPortletString);
        }
        
        url.addPath(urlState.toLowercaseString());
        
        //File part specifying the type of URL
        TYPE urlType = portletPortalUrl.getType();
        switch (urlType) {
            case ACTION: {
                url.addPath(UrlType.ACTION.toLowercaseString() + REQUEST_TYPE_SUFFIX);
            }
            break;
            
            case RENDER: {
                url.addPath(UrlType.RENDER.toLowercaseString() + REQUEST_TYPE_SUFFIX);
            }
            break;
            
            case RESOURCE: {
            	url.addPath(UrlType.RESOURCE.toLowercaseString() + REQUEST_TYPE_SUFFIX);
            	final String cacheability = portletPortalUrl.getCacheability();
                if(cacheability != null) {
                	url.addParameter(PARAM_PORTLET_CACHEABILITY, cacheability);
                }
                
                final String resourceId = portletPortalUrl.getResourceID();
                if(resourceId != null) {
                	url.addParameter(PARAM_PORTLET_RESOURCE_ID, resourceId);
                }
                
                IPortalRequestInfo portalRequestInfo = getPortalRequestInfo(request);
                if(null != portalRequestInfo) {
                	IPortletRequestInfo portletRequestInfo = portalRequestInfo.getPortletRequestInfo();
                	if(null != portletRequestInfo && portletWindowId.equals(portletRequestInfo.getTargetWindowId())) {	
                		url.addParameters(PORTLET_PARAM_PREFIX, portalRequestInfo.getPortletRequestInfo().getPortletParameters());
                	}
                	else {
                		url.addParameters(PORTLET_PARAM_PREFIX, ParameterMap.convertArrayMap(portletWindow.getPreviousPublicRenderParameters()));
                		url.addParameter(PARAM_WINDOW_STATE, portletWindow.getWindowState().toString());
                		url.addParameter(PARAM_PORTLET_MODE, portletWindow.getPortletMode().toString());
                	}
                }
            }
            break;
            
            default: {
                throw new IllegalArgumentException("Unsupported URL type: " + portletPortalUrl.getType()); 
            }
        }
        
          
        //Portlet mode info
        final PortletMode portletMode = portletPortalUrl.getPortletMode();
        if (portletMode != null && !portletMode.equals(portletWindow.getPortletMode())) {
            url.addParameter(PARAM_PORTLET_MODE, portletMode.toString());
        } 
        
        //Add window state param for switching between normal and minimized
        if (requestedWindowState != null && !requestedWindowState.equals(currentWindowState) 
                && (WindowState.MINIMIZED.equals(urlWindowState) || WindowState.NORMAL.equals(urlWindowState))) {
            url.addParameter(PARAM_WINDOW_STATE, requestedWindowState.toString());
        }
        
        //Add all portal parameters
        final Map<String, List<String>> portalParameters = portletPortalUrl.getPortalParameters();
        url.addParameters(PORTAL_PARAM_PREFIX, portalParameters);

        //Add all portlet parameters
        
        final Map<String, String[]> portletParameters = portletPortalUrl.getPortletParameters();
        if(TYPE.RESOURCE.equals(urlType)) {
        	url.addParametersArray(PORTLET_RESOURCE_PARAM_PREFIX, portletParameters);
        } else {
        	url.addParametersArray(PORTLET_PARAM_PREFIX, portletParameters);
        }
        
        if(logger.isDebugEnabled()) {
            logger.debug("Generated '" + url + "' from '" + portletPortalUrl);
        }
        return url.toString();
    }
    
	protected String verifyLayoutNodeId(HttpServletRequest request, String folderNodeId) {
        final IUserInstance userInstance = this.userInstanceManager.getUserInstance(request);
        final IUserPreferencesManager preferencesManager = userInstance.getPreferencesManager();
        final IUserLayoutManager userLayoutManager = preferencesManager.getUserLayoutManager();
        final IUserLayoutNodeDescription node = userLayoutManager.getNode(folderNodeId);
        
        if (node == null) {
            throw new IllegalArgumentException("No layout node exists for id: " + folderNodeId);
        }
        
        final String resolvedFolderId = node.getId();
        return resolvedFolderId;
    }
    
    /**
     * Tries to determine the encoded from the request, if not available falls back to configured default.
     * 
     * @param request The current request.
     * @return The encoding to use.
     */
    protected String getEncoding(HttpServletRequest request) {
        final String encoding = request.getCharacterEncoding();
        if (encoding != null) {
            return encoding;
        }
        
        return this.defaultEncoding;
    }

    /**
     * @param request The current requet
     * @return The base URL, will be absolute (start with / or protocol://) and will end with a /
     */
    protected String getCleanedContextPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        
        //Make sure the URL starts with a /
        if (contextPath.charAt(0) == '/') {
            contextPath = contextPath.substring(1);
        }
        
        //Make sure the URL ends with a /
        if (contextPath.charAt(contextPath.length() - 1) == '/') {
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }

        return contextPath;
    }
    
    /**
     * Returns an {@link XPathExpression} that represents the specified channel NodeId.
     * 
     * TODO this is a layout structure specific XPath expression, this needs to be configurable
     * 
     * @param channelNodeId
     * @return
     */
    protected XPathExpression getFindChannelTabIdExpression(String channelNodeId) {
        final String expression = "/layout/folder/folder[descendant::channel[@ID='" + channelNodeId + "']]/@ID";
        
        final XPathFactory xPathFactory = XPathFactory.newInstance();
        final XPath xPath = xPathFactory.newXPath();
        try {
            //TODO compile the expression once and cache it!
            return xPath.compile(expression);
        }
        catch (XPathExpressionException e) {
            throw new IllegalArgumentException("Invalid XPath expression: " + expression, e);
        }
    }
    
    /**
     * Returns an {@link XPathExpression} that represents the specified tab NodeId.
     * 
     * TODO this is a layout structure specific XPath expression, this needs to be configurable
     * 
     * @param tabNodeId
     * @return
     */
    protected XPathExpression getFindTabIdExpression(String tabNodeId) {
        final String expression = "/layout/folder/folder[@ID='" + tabNodeId + "']/@ID";
        
        final XPathFactory xPathFactory = XPathFactory.newInstance();
        final XPath xPath = xPathFactory.newXPath();
        try {
            //TODO compile the expression once and cache it!
            return xPath.compile(expression);
        }
        catch (XPathExpressionException e) {
            throw new IllegalArgumentException("Invalid XPath expression: " + expression, e);
        }
    }

    /**
     * Returns an {@link XPathExpression} that represent's the user's default tab.
     * 
     * @return
     */
    protected XPathExpression getUserDefaultTabIdExpression() {
    	final String expression = "/layout/folder/folder[@type='regular' and @hidden!='true'][1]/@ID";
    	
    	final XPathFactory xPathFactory = XPathFactory.newInstance();
        final XPath xPath = xPathFactory.newXPath();
        try {
            //TODO compile the expression once and cache it!
            return xPath.compile(expression);
        }
        catch (XPathExpressionException e) {
            throw new IllegalArgumentException("Invalid XPath expression: " + expression, e);
        }
    }
	
}
