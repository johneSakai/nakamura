/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.sakaiproject.nakamura.resource.lite.servlet.post.operations;

import com.google.common.collect.Maps;

import org.apache.sling.api.SlingConstants;
import org.apache.sling.api.SlingException;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.servlets.HtmlResponse;
import org.apache.sling.servlets.post.Modification;
import org.apache.sling.servlets.post.NodeNameGenerator;
import org.apache.sling.servlets.post.SlingPostConstants;
import org.sakaiproject.nakamura.api.lite.StorageClientException;
import org.sakaiproject.nakamura.api.lite.accesscontrol.AccessDeniedException;
import org.sakaiproject.nakamura.api.lite.content.Content;
import org.sakaiproject.nakamura.api.lite.content.ContentManager;
import org.sakaiproject.nakamura.api.resource.DateParser;
import org.sakaiproject.nakamura.api.resource.lite.AbstractSparseCreateOperation;
import org.sakaiproject.nakamura.api.resource.lite.SparseNonExistingResource;
import org.sakaiproject.nakamura.api.resource.lite.SparseRequestProperty;
import org.sakaiproject.nakamura.resource.lite.servlet.post.helper.SparseFileUploadHandler;
import org.sakaiproject.nakamura.resource.lite.servlet.post.helper.SparsePropertyValueHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;

/**
 * The <code>ModifyOperation</code> class implements the default operation called by the
 * Sling default POST servlet if no operation is requested by the client. This operation
 * is able to create and/or modify content.
 */
public class ModifyOperation extends AbstractSparseCreateOperation {

  private final DateParser dateParser;

  /**
   * handler that deals with file upload
   */
  private final SparseFileUploadHandler uploadHandler;

  public ModifyOperation(NodeNameGenerator defaultNodeNameGenerator,
      DateParser dateParser, ServletContext servletContext) {
    super(defaultNodeNameGenerator);
    this.dateParser = dateParser;
    this.uploadHandler = new SparseFileUploadHandler(servletContext);
  }

  @Override
  protected void doRun(SlingHttpServletRequest request, HtmlResponse response,
      ContentManager contentManager, List<Modification> changes, String contentPath) throws StorageClientException, AccessDeniedException, IOException  {

    Map<String, SparseRequestProperty> reqProperties = collectContent(request, response, contentPath);



    for (SparseRequestProperty property : reqProperties.values()) {
      if (property.hasRepositoryMoveSource()) {
        String from = property.getRepositorySource();
        String to = property.getContentPath();
        contentManager.move(from, to);
        changes.add(Modification.onMoved(from, property.getPath()));
        property.setDelete(false);
      } else if (property.hasRepositoryCopySource()) {
        String from = property.getRepositorySource();
        String to = property.getContentPath();
        contentManager.copy(from, to, true);
        changes.add(Modification.onCopied(from, property.getPath()));
        property.setDelete(false);
      } else if ( property.isDelete()) {
        contentManager.delete(property.getContentPath());
        changes.add(Modification.onDeleted(property.getPath()));
      }
    }


    SparsePropertyValueHandler propHandler = new SparsePropertyValueHandler(dateParser, changes);

    Map<String, Content> contentMap = Maps.newHashMap();
    for (SparseRequestProperty prop : reqProperties.values()) {
      if (prop.hasValues()) {
        String propContentPath = prop.getContentPath();
        Content content = contentMap.get(propContentPath);
        if ( content == null  ) {
          content = contentManager.get(propContentPath);
          if ( content == null ) {
            response.setCreateRequest(true);
            content = new Content(propContentPath, null);
          }
          contentMap.put(propContentPath, content);
        }
        // skip jcr special properties
        if (prop.getName().equals("jcr:primaryType")
            || prop.getName().equals("jcr:mixinTypes")) {
          continue;
        }
        if (prop.isFileUpload()) {
          uploadHandler.setFile(content, contentManager, prop, changes);
        } else {
          propHandler.setProperty(content, prop);
        }
      }
    }

    for ( Content c : contentMap.values()) {
      contentManager.update(c);
    }
  }

  @Override
  protected String getItemPath(SlingHttpServletRequest request) {

    // calculate the paths
    StringBuffer rootPathBuf = new StringBuffer();
    String suffix;
    Resource currentResource = request.getResource();
    if (currentResource instanceof SparseNonExistingResource) {
      // no resource, treat the target Content path as suffix
      SparseNonExistingResource nonExistingResource = (SparseNonExistingResource) currentResource;
      suffix = nonExistingResource.getTargetContentPath();
    } else if (ResourceUtil.isSyntheticResource(currentResource)) {

      // no resource, treat the missing resource path as suffix
      suffix = currentResource.getPath();

    } else {

      // resource for part of the path, use request suffix
      suffix = request.getRequestPathInfo().getSuffix();

      // cut off any selectors/extension from the suffix
      if (suffix != null) {
        int dotPos = suffix.indexOf('.');
        if (dotPos > 0) {
          suffix = suffix.substring(0, dotPos);
        }
      }

      // and preset the path buffer with the content path
      Content content = currentResource.adaptTo(Content.class);
      if (content != null) {
        rootPathBuf.append(content.getPath());
      } else {
        rootPathBuf.append(currentResource.getPath());
      }

    }

    // check for star or create suffix
    boolean doGenerateName = false;
    if (suffix != null) {

      // check whether it is a create request (trailing /)
      if (suffix.endsWith(SlingPostConstants.DEFAULT_CREATE_SUFFIX)) {
        suffix = suffix.substring(0, suffix.length()
            - SlingPostConstants.DEFAULT_CREATE_SUFFIX.length());
        doGenerateName = true;

        // or with the star suffix /*
      } else if (suffix.endsWith(SlingPostConstants.STAR_CREATE_SUFFIX)) {
        suffix = suffix.substring(0, suffix.length()
            - SlingPostConstants.STAR_CREATE_SUFFIX.length());
        doGenerateName = true;
      }

      // append the remains of the suffix to the path buffer
      rootPathBuf.append(suffix);

    }

    String path = rootPathBuf.toString();

    if (doGenerateName) {
      try {
        path = generateName(request, path);
      } catch (StorageClientException re) {
        throw new SlingException("Failed to generate name", re);
      }
    }

    return path;
  }

  protected String getFinalResourcePath(SlingHttpServletRequest request, String finalContentPath) {
    Resource resource = request.getResource();
    String resourcePath = resource.getPath();
    String originalContentPath;
    Content content = resource.adaptTo(Content.class);
    if (content != null) {
      originalContentPath = content.getPath();
    } else {
      originalContentPath =  resource.getPath();
    }
    if (finalContentPath.startsWith(originalContentPath)) {
      String suffix = finalContentPath.substring(originalContentPath.length());
      // TODO BL120 this is a temporary fix: I am getting back incorrect paths
      // i.e. the correct path appended to itself
      if (!resourcePath.endsWith(suffix)) {
        resourcePath = resourcePath + suffix;
      }
    }
    
    // TODO BL120 this should not be happening, but if a twiddle path gets through here,
    // Of course, sparse cannot make heads or tails of it
    if (resourcePath.startsWith("/~")) {
      return originalContentPath;
    } else {
      return resourcePath;
    }
  }

}
