package com.github.untalluisnava.core.models;

import com.adobe.cq.export.json.ComponentExporter;
import com.adobe.cq.export.json.ExporterConstants;
import com.adobe.cq.wcm.core.components.models.List;
import com.adobe.cq.wcm.core.components.models.ListItem;
import com.day.cq.commons.jcr.JcrConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.commons.WCMUtils;
import com.github.untalluisnava.core.services.SimilaritySearchService;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import javax.annotation.PostConstruct;
import javax.inject.Inject;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.Exporter;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author lnava
 */
@Model(
    adaptables = SlingHttpServletRequest.class,
    adapters = {List.class, ComponentExporter.class},
    resourceType = SimilarityListImpl.RESOURCE_TYPE)
@Exporter(
    name = ExporterConstants.SLING_MODEL_EXPORTER_NAME,
    extensions = ExporterConstants.SLING_MODEL_EXTENSION)
public class SimilarityListImpl implements List {

  public static final String RESOURCE_TYPE = "aem-pocs/components/list";

  private final Logger log = LoggerFactory.getLogger(getClass());

  @SlingObject private Resource currentResource;
  @SlingObject private ResourceResolver resourceResolver;

  @Inject SimilaritySearchService similaritySearchService;

  @ValueMapValue(name = List.PN_LINK_ITEMS, injectionStrategy = InjectionStrategy.OPTIONAL)
  private boolean linkItems;

  @ValueMapValue(name = List.PN_SHOW_DESCRIPTION, injectionStrategy = InjectionStrategy.OPTIONAL)
  private boolean showDescription;

  @ValueMapValue(
      name = List.PN_SHOW_MODIFICATION_DATE,
      injectionStrategy = InjectionStrategy.OPTIONAL)
  private boolean showModificationDate;

  @ValueMapValue(
      name = List.PN_DISPLAY_ITEM_AS_TEASER,
      injectionStrategy = InjectionStrategy.OPTIONAL)
  private boolean displayItemAsTeaser;

  @ValueMapValue(name = List.PN_DATE_FORMAT, injectionStrategy = InjectionStrategy.OPTIONAL)
  private String dateFormatString;

  @Default(intValues = 10)
  @ValueMapValue(name = "maxItems", injectionStrategy = InjectionStrategy.OPTIONAL)
  private int maxItems;

  private final Collection<Page> pages = new LinkedList<>();

  @PostConstruct
  protected void init() {

    log.info("Instantiating model for resource located at {}", currentResource.getPath());

    Optional.ofNullable(resourceResolver.adaptTo(PageManager.class))
        .map(pageManager -> pageManager.getContainingPage(currentResource))
        .map(
            page ->
                similaritySearchService.getMostSimilarPages(page).stream().limit(maxItems).toList())
        .ifPresent(pages::addAll);
  }

  @Override
  public Collection<ListItem> getListItems() {
    String teaserDelegate =
        Optional.ofNullable(WCMUtils.getComponent(currentResource))
            .map(component -> component.getProperties().get(List.PN_TEASER_DELEGATE, String.class))
            .orElse(null);

    Collection<ListItem> listItems = new ArrayList<>();
    for (Page page : pages) {
      listItems.add(new PageListItem(page, teaserDelegate, linkItems, showDescription));
    }
    return Collections.unmodifiableCollection(listItems);
  }

  @Override
  public Collection<Page> getItems() {
    return Collections.unmodifiableCollection(pages);
  }

  @Override
  public boolean linkItems() {
    return linkItems;
  }

  @Override
  public boolean showDescription() {
    return showDescription;
  }

  @Override
  public boolean showModificationDate() {
    return showModificationDate;
  }

  @Override
  public boolean displayItemAsTeaser() {
    return displayItemAsTeaser;
  }

  @Override
  public String getDateFormatString() {
    return dateFormatString;
  }

  private static final class PageListItem implements ListItem {
    private final Page page;
    private final String teaserDelegate;
    private final boolean linkItems;
    private final boolean showDescription;

    private PageListItem(
        Page page, String teaserDelegate, boolean linkItems, boolean showDescription) {
      this.page = page;
      this.teaserDelegate = teaserDelegate;
      this.linkItems = linkItems;
      this.showDescription = showDescription;
    }

    @Override
    public String getTitle() {
      return Optional.ofNullable(page.getNavigationTitle())
          .orElseGet(() -> Optional.ofNullable(page.getTitle()).orElseGet(page::getName));
    }

    @Override
    public String getDescription() {
      return page.getDescription();
    }

    @Override
    public String getPath() {
      return page.getPath();
    }

    @Override
    public String getName() {
      return page.getName();
    }

    @Override
    public String getURL() {
      return page.getPath() + ".html";
    }

    @Override
    public Calendar getLastModified() {
      return page.getLastModified();
    }

    @Override
    public Resource getTeaserResource() {
      Resource contentResource = page.getContentResource();
      if (teaserDelegate == null || contentResource == null) {
        return null;
      }

      // Copia mutable de las propiedades del jcr:content de la página, ajustada
      // según las opciones del diálogo, para alimentar al teaserDelegate.
      Map<String, Object> properties = new HashMap<>(contentResource.getValueMap());
      if (!showDescription) {
        properties.remove(JcrConstants.JCR_DESCRIPTION);
      }
      if (linkItems) {
        properties.put("linkURL", page.getPath());
      }

      return new ResourceWrapper(contentResource) {
        @Override
        public String getResourceType() {
          return teaserDelegate;
        }

        @Override
        public ValueMap getValueMap() {
          return new ValueMapDecorator(properties);
        }
      };
    }
  }
}
