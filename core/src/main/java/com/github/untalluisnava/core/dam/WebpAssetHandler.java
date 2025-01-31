package com.github.untalluisnava.core.dam;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.DamConstants;
import com.day.cq.dam.api.Rendition;
import com.day.cq.dam.api.handler.AssetHandler;
import com.day.cq.dam.api.metadata.ExtractedMetadata;
import com.day.cq.dam.commons.handler.StandardImageHandler;
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.commons.imaging.ImageReadException;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author lnava
 */
@Component(service = AssetHandler.class)
public class WebpAssetHandler extends StandardImageHandler {

  private final Logger logger = LoggerFactory.getLogger(WebpAssetHandler.class);

  private static final String MIME_TYPE = "image/webp";

  private final WebPImageReaderSpi imageReaderSpi;

  public WebpAssetHandler() {
    imageReaderSpi = new WebPImageReaderSpi();
  }

  @Override
  public String[] getMimeTypes() {
    return new String[] {MIME_TYPE};
  }

  @Override
  public BufferedImage getImage(Rendition rendition, Dimension maxDimension) {
    try (InputStream stream = rendition.getStream();
        ImageInputStream imageInputStream = ImageIO.createImageInputStream(stream)) {

      if (!imageReaderSpi.canDecodeInput(imageInputStream)) {
        return null;
      }

      ImageReader imageReader = null;

      try {
        imageReader = imageReaderSpi.createReaderInstance();
        imageReader.setInput(imageInputStream, true, true);
        return imageReader.read(0);
      } finally {
        Optional.ofNullable(imageReader).ifPresent(reader -> reader.dispose());
      }

    } catch (Exception e) {
      logger.error("I couldn't extract an image.", e);
    }
    return null;
  }

  @Override
  protected void extractMetadata(Asset asset, ExtractedMetadata metadata)
      throws IOException, ImageReadException {
    try (InputStream stream = asset.getOriginal().getStream();
        ImageInputStream imageInputStream = ImageIO.createImageInputStream(stream)) {

      if (!imageReaderSpi.canDecodeInput(imageInputStream)) {
        return;
      }

      ImageReader imageReader = null;

      try {

        imageReader = imageReaderSpi.createReaderInstance();

        imageReader.setInput(imageInputStream, true, true);

        int width = imageReader.getWidth(0);
        int height = imageReader.getHeight(0);

        metadata.setMetaDataProperty(DamConstants.TIFF_IMAGEWIDTH, width);
        metadata.setMetaDataProperty(DamConstants.TIFF_IMAGELENGTH, height);

      } finally {
        Optional.ofNullable(imageReader).ifPresent(reader -> reader.dispose());
      }
    }
  }
}
