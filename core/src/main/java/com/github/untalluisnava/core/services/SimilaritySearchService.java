package com.github.untalluisnava.core.services;

import com.day.cq.wcm.api.Page;
import java.util.List;

/**
 * @author lnava
 */
public interface SimilaritySearchService {

  List<Page> getMostSimilarPages(Page reference);
}
