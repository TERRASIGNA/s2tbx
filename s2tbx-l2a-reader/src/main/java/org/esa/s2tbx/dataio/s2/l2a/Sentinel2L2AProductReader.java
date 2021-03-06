/*
 *
 * Copyright (C) 2013-2014 Brockmann Consult GmbH (info@brockmann-consult.de)
 * Copyright (C) 2014-2015 CS SI
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *  This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 *
 */

package org.esa.s2tbx.dataio.s2.l2a;

import com.bc.ceres.core.Assert;
import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.glevel.MultiLevelImage;
import com.bc.ceres.glevel.support.AbstractMultiLevelSource;
import com.bc.ceres.glevel.support.DefaultMultiLevelImage;
import com.bc.ceres.glevel.support.DefaultMultiLevelModel;
import jp2.TileLayout;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.esa.s2tbx.dataio.Utils;
import org.esa.s2tbx.dataio.s2.l2a.filepatterns.S2L2aProductFilename;
import org.esa.snap.framework.dataio.AbstractProductReader;
import org.esa.snap.framework.dataio.ProductReaderPlugIn;
import org.esa.snap.framework.datamodel.Band;
import org.esa.snap.framework.datamodel.CrsGeoCoding;
import org.esa.snap.framework.datamodel.Product;
import org.esa.snap.framework.datamodel.ProductData;
import org.esa.snap.framework.datamodel.TiePointGrid;
import org.esa.snap.jai.ImageManager;
import org.esa.snap.util.SystemUtils;
import org.esa.snap.util.io.FileUtils;
import org.esa.snap.util.logging.BeamLogManager;
import org.geotools.geometry.Envelope2D;
import org.jdom.JDOMException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;
import org.openjpeg.StackTraceUtils;

import javax.media.jai.ImageLayout;
import javax.media.jai.Interpolation;
import javax.media.jai.JAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.MosaicDescriptor;
import javax.media.jai.operator.TranslateDescriptor;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.esa.s2tbx.dataio.s2.l2a.ImageInfoPredicates.*;
import static org.esa.s2tbx.dataio.s2.l2a.L2aMetadata.*;
import static org.esa.s2tbx.dataio.s2.l2a.S2L2AConfig.*;

// todo - register reasonable RGB profile(s)
// todo - set a band's validMaskExpr or no-data value (read from GML)
// todo - set band's ImageInfo from min,max,histogram found in header (--> L1cMetadata.quicklookDescriptor)
// todo - viewing incidence tie-point grids contain NaN values - find out how to correctly treat them
// todo - configure BEAM module / SUHET installer so that OpenJPEG "opj_decompress" executable is accessible on all platforms

// todo - better collect problems during product opening and generate problem report (requires reader API change), see {@report "Problem detected..."} code marks

/**
 * <p>
 * This product reader can currently read single L1C tiles (also called L1C granules) and entire L1C scenes composed of
 * multiple L1C tiles.
 * </p>
 * <p>
 * To read single tiles, select any tile image file (IMG_*.jp2) within a product package. The reader will then
 * collect other band images for the selected tile and wiull also try to read the metadata file (MTD_*.xml).
 * </p>
 * <p>To read an entire scene, select the metadata file (MTD_*.xml) within a product package. The reader will then
 * collect other tile/band images and create a mosaic on the fly.
 * </p>
 *
 * @author Norman Fomferra
 */
public class Sentinel2L2AProductReader extends AbstractProductReader {

    private final boolean forceResize;

    private File cacheDir;
    protected final Logger logger;
    private int filteredResolution;

    static class BandInfo {
        final Map<String, File> tileIdToFileMap;
        final int bandIndex;
        final S2L2AWavebandInfo wavebandInfo;
        final TileLayout imageLayout;

        BandInfo(Map<String, File> tileIdToFileMap, int bandIndex, S2L2AWavebandInfo wavebandInfo, TileLayout imageLayout) {
            this.tileIdToFileMap = Collections.unmodifiableMap(tileIdToFileMap);
            this.bandIndex = bandIndex;
            this.wavebandInfo = wavebandInfo;
            this.imageLayout = imageLayout;
        }

        public S2L2AWavebandInfo getWavebandInfo() {
            return wavebandInfo;
        }

        public String toString() {
            return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
        }
    }

    public Sentinel2L2AProductReader(ProductReaderPlugIn readerPlugIn, boolean forceResize, int filteredResolution) {
        super(readerPlugIn);
        logger = BeamLogManager.getSystemLogger();
        this.forceResize = forceResize;
        this.filteredResolution = filteredResolution;
    }

    Sentinel2L2AProductReader(ProductReaderPlugIn readerPlugIn, boolean forceResize) {
        super(readerPlugIn);
        logger = BeamLogManager.getSystemLogger();
        this.forceResize = forceResize;
        this.filteredResolution = -1;
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight, int sourceStepX, int sourceStepY, Band destBand, int destOffsetX, int destOffsetY, int destWidth, int destHeight, ProductData destBuffer, ProgressMonitor pm) throws IOException {
        // Should never not come here, since we have an OpImage that reads data
    }

    @Override
    protected Product readProductNodesImpl() throws IOException {
        logger.fine("readProductNodeImpl, " + getInput().toString());

        Product p = null;

        final File inputFile = new File(getInput().toString());
        if (!inputFile.exists()) {
            throw new FileNotFoundException(inputFile.getPath());
        }

        if (S2L2aProductFilename.isProductFilename(inputFile.getName())) {
            boolean isAGranule = S2L2aProductFilename.isGranuleFilename(inputFile.getName());
            if(isAGranule)
            {
                logger.fine("Reading a granule");
            }

            p = getL1cMosaicProduct(inputFile, isAGranule);

            if (p != null) {
                readMasks(p);
                initGeoCoding(p);
                p.setModified(false);
            }
        } else {
            throw new IOException("Unhandled file type.");
        }

        return p;
    }

    private void initGeoCoding(Product p) {
        Assert.notNull(p);
        // fixme Implement this
    }

    private void readMasks(Product p) {
        Assert.notNull(p);
        // fixme Implement this
    }

    private Product getL1cMosaicProduct(File granuleMetadataFile, boolean isAGranule) throws IOException {
        Objects.requireNonNull(granuleMetadataFile);
        // first we need to recover parent metadata file...

        String filterTileId = null;
        File metadataFile = null;
        if(isAGranule)
        {
            try
            {
                Objects.requireNonNull(granuleMetadataFile.getParentFile());
                Objects.requireNonNull(granuleMetadataFile.getParentFile().getParentFile());
                Objects.requireNonNull(granuleMetadataFile.getParentFile().getParentFile().getParentFile());
            } catch (NullPointerException npe)
            {
                throw new IOException(String.format("Unable to retrieve the product associated to granule metadata file [%s]", granuleMetadataFile.getName()));
            }

            File up2levels = granuleMetadataFile.getParentFile().getParentFile().getParentFile();
            File tileIdFilter = granuleMetadataFile.getParentFile();

            filterTileId = tileIdFilter.getName();

            File[] files = up2levels.listFiles();
            for(File f: files)
            {
                if(S2L2aProductFilename.isProductFilename(f.getName()) && S2L2aProductFilename.isMetadataFilename(f.getName()))
                {
                    metadataFile = f;
                    break;
                }
            }
            if(metadataFile == null)
            {
                throw new IOException(String.format("Unable to retrieve the product associated to granule metadata file [%s]", granuleMetadataFile.getName()));
            }
        }
        else
        {
            metadataFile = granuleMetadataFile;
        }

        final String aFilter = filterTileId;

        L2aMetadata metadataHeader;

        try {
            metadataHeader = parseHeader(metadataFile);
        } catch (JDOMException e) {
            BeamLogManager.getSystemLogger().severe(Utils.getStackTrace(e));
            throw new IOException("Failed to parse metadata in " + metadataFile.getName());
        }

        L2aSceneDescription sceneDescription = L2aSceneDescription.create(metadataHeader, Tile.idGeom.G10M);
        logger.fine("Scene Description: " + sceneDescription);

        File productDir = getProductDir(metadataFile);
        initCacheDir(productDir);

        ProductCharacteristics productCharacteristics = metadataHeader.getProductCharacteristics();

        Map<Integer, BandInfo> bandInfoMap = new HashMap<Integer, BandInfo>();

        List<L2aMetadata.Tile> tileList = metadataHeader.getTileList();
        if(isAGranule)
        {
            tileList = tileList.stream().filter(p -> p.id.equalsIgnoreCase(aFilter)).collect(Collectors.toList());
        }

        Collection<ImageInfo> imageList = metadataHeader.getImageList();
        if (imageList.isEmpty()) {
            logger.warning("No images detected !!");
        }

        if (tileList.isEmpty()) {
            logger.warning("Empty tile list !");
        }
        for (final SpectralInformation bandInformation : productCharacteristics.bandInformations) {
            int bandIndex = bandInformation.bandId;
            if (bandIndex >= 0 && bandIndex < productCharacteristics.bandInformations.length) {

                HashMap<String, File> tileFileMap = new HashMap<String, File>();
                for (Tile tile : tileList) {
                    // todo filter by band and by tile.id imageList
                    List<ImageInfo> filteredImages = filterImageInfo(imageList, isBand(bandInformation.physicalBand), isGranule(tile.id), isJPEG2000());

                    for (ImageInfo imageFound : filteredImages) {
                        // todo what if files are "binary" ?
                        String dependantFilePath = "";
                        if (imageFound.getFileName().contains("10m")) {
                            dependantFilePath = "R10m" + File.separator;
                        } else if (imageFound.getFileName().contains("20m")) {
                            dependantFilePath = "R20m" + File.separator;
                        } else if (imageFound.getFileName().contains("60m")) {
                            dependantFilePath = "R60m" + File.separator;
                        }

                        String imgFilename = "GRANULE" + File.separator + tile.id + File.separator + "IMG_DATA" + File.separator + dependantFilePath + imageFound.getFileName() + ".jp2";
                        String fallbackImgFilename = "GRANULE" + File.separator + tile.id + File.separator + "IMG_DATA" + File.separator + imageFound.getFileName() + ".jp2";


                        File file = new File(productDir, imgFilename);
                        if (file.exists()) {
                            logger.fine("Adding file " + imgFilename + " to band: " + bandInformation.physicalBand);
                            tileFileMap.put(tile.id, file);
                        } else {
                            File fallback = new File(productDir, fallbackImgFilename);
                            if (fallback.exists()) {
                                tileFileMap.put(tile.id, file);
                            } else {
                                logger.warning(String.format("Warning: missing file %s\n", file));
                            }
                        }
                    }
                }

                if (!tileFileMap.isEmpty()) {
                    BandInfo bandInfo = createBandInfoFromHeaderInfo(bandInformation, tileFileMap);
                    bandInfoMap.put(bandIndex, bandInfo);
                } else {
                    logger.warning(String.format("Warning: no image files found for band %s\n", bandInformation.physicalBand));
                }
            } else {
                logger.warning(String.format("Warning: illegal band index detected for band %s\n", bandInformation.physicalBand));
            }
        }

        //todo change product filename properties...
        //todo test saving modified product...
        Product product = new Product(FileUtils.getFilenameWithoutExtension(metadataFile),
                                      "S2_MSI_" + productCharacteristics.processingLevel,
                                      sceneDescription.getSceneRectangle().width,
                                      sceneDescription.getSceneRectangle().height);

        product.getMetadataRoot().addElement(metadataHeader.getMetadataElement());
        product.setFileLocation(metadataFile.getParentFile());

        // setStartStopTime(product, mtdFilename.start, mtdFilename.stop);
        if(forceResize) {
            setGeoCoding(product, sceneDescription.getSceneEnvelope());
        }

        //todo look at affine tranformation geocoding info...
        if(!bandInfoMap.isEmpty())
        {
            addBands(product, bandInfoMap, sceneDescription.getSceneEnvelope(), new L2aSceneMultiLevelImageFactory(sceneDescription, ImageManager.getImageToModelTransform(product.getGeoCoding())));
            addTiePointGridBand(product, metadataHeader, sceneDescription, "sun_zenith", 0);
            addTiePointGridBand(product, metadataHeader, sceneDescription, "sun_azimuth", 1);
            addTiePointGridBand(product, metadataHeader, sceneDescription, "view_zenith", 2);
            addTiePointGridBand(product, metadataHeader, sceneDescription, "view_azimuth", 3);
        }

        //todo there is more data in product metadata file, should we preload it ?

        return product;
    }

    private void addTiePointGridBand(Product product, L2aMetadata metadataHeader, L2aSceneDescription sceneDescription, String name, int tiePointGridIndex) {
        final Band band = product.addBand(name, ProductData.TYPE_FLOAT32);
        band.setSourceImage(new DefaultMultiLevelImage(new TiePointGridL2aSceneMultiLevelSource(sceneDescription, metadataHeader, ImageManager.getImageToModelTransform(product.getGeoCoding()), 6, tiePointGridIndex)));
    }

    private void addBands(Product product, Map<Integer, BandInfo> bandInfoMap, Envelope2D envelope, MultiLevelImageFactory mlif) throws IOException {
        product.setPreferredTileSize(DEFAULT_JAI_TILE_SIZE, DEFAULT_JAI_TILE_SIZE);
        product.setNumResolutionsMax(L2A_TILE_LAYOUTS[0].numResolutions);
        product.setAutoGrouping("reflec:radiance:sun:view");

        ArrayList<Integer> bandIndexes = new ArrayList<Integer>(bandInfoMap.keySet());
        Collections.sort(bandIndexes);

        if (bandIndexes.isEmpty()) {
            throw new IOException("No valid bands found.");
        }

        for (Integer bandIndex : bandIndexes) {
            BandInfo bandInfo = bandInfoMap.get(bandIndex);
            if (this.filteredResolution == -1 || bandInfo.getWavebandInfo().resolution.resolution == this.filteredResolution)
            {
                Band band = addBand(product, bandInfo);
                band.setSourceImage(mlif.createSourceImage(bandInfo));

                if (!forceResize) {
                    try {
                        band.setGeoCoding(new CrsGeoCoding(envelope.getCoordinateReferenceSystem(),
                                band.getRasterWidth(),
                                band.getRasterHeight(),
                                envelope.getMinX(),
                                envelope.getMaxY(),
                                bandInfo.getWavebandInfo().resolution.resolution,
                                bandInfo.getWavebandInfo().resolution.resolution,
                                0.0, 0.0));
                    } catch (FactoryException e) {
                        logger.severe("Illegal CRS");
                    } catch (TransformException e) {
                        logger.severe("Illegal projection");
                    }
                }
            }
        }


    }

    private Band addBand(Product product, BandInfo bandInfo) {
        int index = S2SpatialResolution.valueOfId(bandInfo.getWavebandInfo().resolution.id).resolution / S2SpatialResolution.R10M.resolution;
        int defRes = S2SpatialResolution.R10M.resolution;

        final Band band = new Band(bandInfo.wavebandInfo.bandName, SAMPLE_PRODUCT_DATA_TYPE, product.getSceneRasterWidth()  / index, product.getSceneRasterHeight()  / index);
        product.addBand(band);

        band.setSpectralBandIndex(bandInfo.bandIndex);
        band.setSpectralWavelength((float) bandInfo.wavebandInfo.wavelength);
        band.setSpectralBandwidth((float) bandInfo.wavebandInfo.bandwidth);


        //todo add masks from GML metadata files (gml branch)
        setValidPixelMask(band, bandInfo.wavebandInfo.bandName);

        // todo - We don't use the scaling factor because we want to stay with 16bit unsigned short samples due to the large
        // amounts of data when saving the images. We provide virtual reflectance bands for this reason. We can use the
        // scaling factor again, once we have product writer parameters, so that users can decide to write data as
        // 16bit samples.
        //
        //band.setScalingFactor(bandInfo.wavebandInfo.scalingFactor);

        return band;
    }

    private void setValidPixelMask(Band band, String bandName) {
        band.setNoDataValue(0);
        band.setValidPixelExpression(String.format("%s.raw > %s",
                                                   bandName, S2L2AConfig.RAW_NO_DATA_THRESHOLD));
    }

    private void addL1cTileTiePointGrids(L2aMetadata metadataHeader, Product product, int tileIndex) {
        final TiePointGrid[] tiePointGrids = createL1cTileTiePointGrids(metadataHeader, tileIndex);
        for (TiePointGrid tiePointGrid : tiePointGrids) {
            product.addTiePointGrid(tiePointGrid);
        }
    }

    private TiePointGrid[] createL1cTileTiePointGrids(L2aMetadata metadataHeader, int tileIndex) {
        Tile tile = metadataHeader.getTileList().get(tileIndex);
        int gridHeight = tile.sunAnglesGrid.zenith.length;
        int gridWidth = tile.sunAnglesGrid.zenith[0].length;
        float[] sunZeniths = new float[gridWidth * gridHeight];
        float[] sunAzimuths = new float[gridWidth * gridHeight];
        float[] viewingZeniths = new float[gridWidth * gridHeight];
        float[] viewingAzimuths = new float[gridWidth * gridHeight];
        Arrays.fill(viewingZeniths, Float.NaN);
        Arrays.fill(viewingAzimuths, Float.NaN);
        L2aMetadata.AnglesGrid sunAnglesGrid = tile.sunAnglesGrid;
        L2aMetadata.AnglesGrid[] viewingIncidenceAnglesGrids = tile.viewingIncidenceAnglesGrids;
        for (int y = 0; y < gridHeight; y++) {
            for (int x = 0; x < gridWidth; x++) {
                final int index = y * gridWidth + x;
                sunZeniths[index] = sunAnglesGrid.zenith[y][x];
                sunAzimuths[index] = sunAnglesGrid.azimuth[y][x];
                for (L2aMetadata.AnglesGrid grid : viewingIncidenceAnglesGrids) {
                    try {
                        if( y < grid.zenith.length)
                        {
                            if( x < grid.zenith[y].length)
                            {
                                if (!Float.isNaN(grid.zenith[y][x])) {
                                    viewingZeniths[index] = grid.zenith[y][x];
                                }
                            }
                        }

                        if( y < grid.azimuth.length)
                        {
                            if( x < grid.azimuth[y].length)
                            {
                                if (!Float.isNaN(grid.azimuth[y][x])) {
                                    viewingAzimuths[index] = grid.azimuth[y][x];
                                }
                            }
                        }

                    } catch (Exception e) {
                        // {@report "Solar info problem"}
                        logger.severe(StackTraceUtils.getStackTrace(e));
                    }
                }
            }
        }
        return new TiePointGrid[]{
                createTiePointGrid("sun_zenith", gridWidth, gridHeight, sunZeniths),
                createTiePointGrid("sun_azimuth", gridWidth, gridHeight, sunAzimuths),
                createTiePointGrid("view_zenith", gridWidth, gridHeight, viewingZeniths),
                createTiePointGrid("view_azimuth", gridWidth, gridHeight, viewingAzimuths)
        };
    }

    private TiePointGrid createTiePointGrid(String name, int gridWidth, int gridHeight, float[] values) {
        final TiePointGrid tiePointGrid = new TiePointGrid(name, gridWidth, gridHeight, 0.0F, 0.0F, 500.0F, 500.0F, values);
        tiePointGrid.setNoDataValue(Double.NaN);
        tiePointGrid.setNoDataValueUsed(true);
        return tiePointGrid;
    }

    private static Map<String, File> createFileMap(String tileId, File imageFile) {
        Map<String, File> tileIdToFileMap = new HashMap<String, File>();
        tileIdToFileMap.put(tileId, imageFile);
        return tileIdToFileMap;
    }

    private void setStartStopTime(Product product, String start, String stop) {
        try {
            product.setStartTime(ProductData.UTC.parse(start, "yyyyMMddHHmmss"));
        } catch (ParseException e) {
            // {@report "illegal start date"}
        }

        try {
            product.setEndTime(ProductData.UTC.parse(stop, "yyyyMMddHHmmss"));
        } catch (ParseException e) {
            // {@report "illegal stop date"}
        }
    }

    private BandInfo createBandInfoFromDefaults(int bandIndex, S2L2AWavebandInfo wavebandInfo, String tileId, File imageFile) {
        // TileLayout aLayout = CodeStreamUtils.getTileLayout(imageFile.toURI().toString(), null);
        return new BandInfo(createFileMap(tileId, imageFile),
                            bandIndex,
                            wavebandInfo,
                            // aLayout);
                            //todo test this
                            L2A_TILE_LAYOUTS[wavebandInfo.resolution.id]);

    }

    private BandInfo createBandInfoFromHeaderInfo(SpectralInformation bandInformation, Map<String, File> tileFileMap) {
        S2SpatialResolution spatialResolution = S2SpatialResolution.valueOfResolution(bandInformation.resolution);
        return new BandInfo(tileFileMap,
                            bandInformation.bandId,
                            new S2L2AWavebandInfo(bandInformation.bandId,
                                                  bandInformation.physicalBand,
                                                  spatialResolution, bandInformation.wavelenghtCentral,
                                                  Math.abs(bandInformation.wavelenghtMax + bandInformation.wavelenghtMin)),
                            L2A_TILE_LAYOUTS[spatialResolution.id]);
    }

    private void setGeoCoding(Product product, Envelope2D envelope) {
        try {
            product.setGeoCoding(new CrsGeoCoding(envelope.getCoordinateReferenceSystem(),
                                                  product.getSceneRasterWidth(),
                                                  product.getSceneRasterHeight(),
                                                  envelope.getMinX(),
                                                  envelope.getMaxY(),
                                                  S2SpatialResolution.R10M.resolution,
                                                  S2SpatialResolution.R10M.resolution,
                                                  0.0, 0.0));
        } catch (FactoryException e) {
            logger.severe("Illegal CRS");
        } catch (TransformException e) {
            logger.severe("Illegal projection");
        }
    }

    static File getProductDir(File productFile) throws IOException {
        final File resolvedFile = productFile.getCanonicalFile();
        if (!resolvedFile.exists()) {
            throw new FileNotFoundException("File not found: " + productFile);
        }

        if (productFile.getParentFile() == null) {
            return new File(".").getCanonicalFile();
        }

        return productFile.getParentFile();
    }

    void initCacheDir(File productDir) throws IOException {
        cacheDir = new File(new File(SystemUtils.getApplicationDataDir(), "beam-sentinel2-reader/cache"),
                            productDir.getName());
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        if (!cacheDir.exists() || !cacheDir.isDirectory() || !cacheDir.canWrite()) {
            throw new IOException("Can't access package cache directory");
        }
    }


    private abstract class MultiLevelImageFactory {
        protected final AffineTransform imageToModelTransform;

        protected MultiLevelImageFactory(AffineTransform imageToModelTransform) {
            this.imageToModelTransform = imageToModelTransform;
        }

        public abstract MultiLevelImage createSourceImage(BandInfo bandInfo);
    }

    private class L1cTileMultiLevelImageFactory extends MultiLevelImageFactory {
        private L1cTileMultiLevelImageFactory(AffineTransform imageToModelTransform) {
            super(imageToModelTransform);
        }

        public MultiLevelImage createSourceImage(BandInfo bandInfo) {
            return new DefaultMultiLevelImage(new L1cTileMultiLevelSource(bandInfo, imageToModelTransform));
        }
    }

    private class L2aSceneMultiLevelImageFactory extends MultiLevelImageFactory {

        private final L2aSceneDescription sceneDescription;

        public L2aSceneMultiLevelImageFactory(L2aSceneDescription sceneDescription, AffineTransform imageToModelTransform) {
            super(imageToModelTransform);

            BeamLogManager.getSystemLogger().fine("Model factory: " + ToStringBuilder.reflectionToString(imageToModelTransform));

            this.sceneDescription = sceneDescription;
        }

        @Override
        public MultiLevelImage createSourceImage(BandInfo bandInfo) {
            BandL2aSceneMultiLevelSource bandScene = new BandL2aSceneMultiLevelSource(sceneDescription, bandInfo, imageToModelTransform);
            BeamLogManager.getSystemLogger().log(Level.parse(S2L2AConfig.LOG_SCENE), "BandScene: " + bandScene);
            return new DefaultMultiLevelImage(bandScene);
        }
    }

    /**
     * A MultiLevelSource for single L1C tiles.
     */
    private class L1cTileMultiLevelSource extends AbstractMultiLevelSource {
        final BandInfo bandInfo;

        public L1cTileMultiLevelSource(BandInfo bandInfo, AffineTransform imageToModelTransform) {
            super(new DefaultMultiLevelModel(bandInfo.imageLayout.numResolutions,
                                             imageToModelTransform,
                                             L2A_TILE_LAYOUTS[0].width, //todo we must use data from jp2 files to update this
                                             L2A_TILE_LAYOUTS[0].height)); //todo we must use data from jp2 files to update this
            this.bandInfo = bandInfo;
        }

        @Override
        protected RenderedImage createImage(int level) {
            File imageFile = bandInfo.tileIdToFileMap.values().iterator().next();
            return L2aTileOpImage.create(imageFile,
                                         cacheDir,
                                         null,
                                         bandInfo.imageLayout,
                                         getModel(),
                                         bandInfo.wavebandInfo.resolution,
                                         level);
        }

    }

    /**
     * A MultiLevelSource for a scene made of multiple L1C tiles.
     */
    private abstract class AbstractL2aSceneMultiLevelSource extends AbstractMultiLevelSource {
        protected final L2aSceneDescription sceneDescription;

        AbstractL2aSceneMultiLevelSource(L2aSceneDescription sceneDescription, AffineTransform imageToModelTransform, int numResolutions) {
            super(new DefaultMultiLevelModel(numResolutions,
                                             imageToModelTransform,
                                             sceneDescription.getSceneRectangle().width,
                                             sceneDescription.getSceneRectangle().height));
            this.sceneDescription = sceneDescription;
        }


        protected abstract PlanarImage createL2aTileImage(String tileId, int level);
    }

    /**
     * A MultiLevelSource used by bands for a scene made of multiple L1C tiles.
     */
    private final class BandL2aSceneMultiLevelSource extends AbstractL2aSceneMultiLevelSource {
        private final BandInfo bandInfo;

        public BandL2aSceneMultiLevelSource(L2aSceneDescription sceneDescription, BandInfo bandInfo, AffineTransform imageToModelTransform) {
            super(sceneDescription, imageToModelTransform, bandInfo.imageLayout.numResolutions);
            this.bandInfo = bandInfo;
        }

        @Override
        protected PlanarImage createL2aTileImage(String tileId, int level) {
            File imageFile = bandInfo.tileIdToFileMap.get(tileId);
            PlanarImage planarImage = L2aTileOpImage.create(imageFile,
                                                            cacheDir,
                                                            null, // tileRectangle.getLocation(),
                                                            bandInfo.imageLayout,
                                                            getModel(),
                                                            bandInfo.wavebandInfo.resolution,
                                                            level);

            logger.fine(String.format("Planar image model: %s", getModel().toString()));

            logger.fine(String.format("Planar image created: %s %s: minX=%d, minY=%d, width=%d, height=%d\n",
                                      bandInfo.wavebandInfo.bandName, tileId,
                                      planarImage.getMinX(), planarImage.getMinY(),
                                      planarImage.getWidth(), planarImage.getHeight()));

            return planarImage;
        }

        @Override
        protected RenderedImage createImage(int level) {
            ArrayList<RenderedImage> tileImages = new ArrayList<RenderedImage>();

            for (String tileId : sceneDescription.getTileIds()) {
                int tileIndex = sceneDescription.getTileIndex(tileId);
                Rectangle tileRectangle = sceneDescription.getTileRectangle(tileIndex);

                PlanarImage opImage = createL2aTileImage(tileId, level);

                {
                    double factorX = 1.0 / (Math.pow(2, level) * (this.bandInfo.wavebandInfo.resolution.resolution / S2SpatialResolution.R10M.resolution));
                    double factorY = 1.0 / (Math.pow(2, level) * (this.bandInfo.wavebandInfo.resolution.resolution / S2SpatialResolution.R10M.resolution));

                    opImage = TranslateDescriptor.create(opImage,
                                                         (float) Math.floor((tileRectangle.x * factorX)),
                                                         (float) Math.floor((tileRectangle.y * factorY)),
                                                         Interpolation.getInstance(Interpolation.INTERP_NEAREST), null);

                    logger.fine(String.format("Translate descriptor: %s", ToStringBuilder.reflectionToString(opImage)));
                }

                logger.log(Level.parse(S2L2AConfig.LOG_SCENE), String.format("opImage added for level %d at (%d,%d) with size (%d,%d)%n", level, opImage.getMinX(), opImage.getMinY(), opImage.getWidth(), opImage.getHeight()));
                tileImages.add(opImage);
            }

            if (tileImages.isEmpty()) {
                logger.warning("No tile images for mosaic");
                return null;
            }

            ImageLayout imageLayout = new ImageLayout();
            imageLayout.setMinX(0);
            imageLayout.setMinY(0);
            imageLayout.setTileWidth(DEFAULT_JAI_TILE_SIZE);
            imageLayout.setTileHeight(DEFAULT_JAI_TILE_SIZE);
            imageLayout.setTileGridXOffset(0);
            imageLayout.setTileGridYOffset(0);

            RenderedOp mosaicOp = MosaicDescriptor.create(tileImages.toArray(new RenderedImage[tileImages.size()]),
                                                          MosaicDescriptor.MOSAIC_TYPE_OVERLAY,
                                                          null, null, new double[][]{{1.0}}, new double[]{FILL_CODE_MOSAIC_BG},
                                                          new RenderingHints(JAI.KEY_IMAGE_LAYOUT, imageLayout));

            if (this.bandInfo.wavebandInfo.resolution != S2SpatialResolution.R10M) {
                PlanarImage scaled = L2aTileOpImage.createGenericScaledImage(mosaicOp, sceneDescription.getSceneEnvelope(), this.bandInfo.wavebandInfo.resolution, level, forceResize);

                logger.fine(String.format("mosaicOp created for level %d at (%d,%d) with size (%d, %d)%n", level, scaled.getMinX(), scaled.getMinY(), scaled.getWidth(), scaled.getHeight()));

                return scaled;
            }
            // todo add crop ?

            logger.fine(String.format("mosaicOp created for level %d at (%d,%d) with size (%d, %d)%n", level, mosaicOp.getMinX(), mosaicOp.getMinY(), mosaicOp.getWidth(), mosaicOp.getHeight()));

            return mosaicOp;
        }

        @Override
        public String toString() {
            return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
        }
    }

    /**
     * A MultiLevelSource used by bands for a scene made of multiple L1C tiles.
     */
    private final class TiePointGridL2aSceneMultiLevelSource extends AbstractL2aSceneMultiLevelSource {

        private final L2aMetadata metadata;
        private final int tiePointGridIndex;
        private HashMap<String, TiePointGrid[]> tiePointGridsMap;

        public TiePointGridL2aSceneMultiLevelSource(L2aSceneDescription sceneDescription, L2aMetadata metadata, AffineTransform imageToModelTransform, int numResolutions, int tiePointGridIndex) {
            super(sceneDescription, imageToModelTransform, numResolutions);
            this.metadata = metadata;
            this.tiePointGridIndex = tiePointGridIndex;
            tiePointGridsMap = new HashMap<String, TiePointGrid[]>();
        }

        @Override
        protected PlanarImage createL2aTileImage(String tileId, int level) {
            TiePointGrid[] tiePointGrids = tiePointGridsMap.get(tileId);
            if (tiePointGrids == null) {
                final int tileIndex = sceneDescription.getTileIndex(tileId);
                tiePointGrids = createL1cTileTiePointGrids(metadata, tileIndex);
                tiePointGridsMap.put(tileId, tiePointGrids);
            }
            return (PlanarImage) tiePointGrids[tiePointGridIndex].getSourceImage().getImage(level);
        }

        @Override
        protected RenderedImage createImage(int level) {
            ArrayList<RenderedImage> tileImages = new ArrayList<RenderedImage>();

            for (String tileId : sceneDescription.getTileIds()) {

                int tileIndex = sceneDescription.getTileIndex(tileId);
                Rectangle tileRectangle = sceneDescription.getTileRectangle(tileIndex);

                PlanarImage opImage = createL2aTileImage(tileId, level);

                // todo - This translation step is actually not required because we can create L1cTileOpImages
                // with minX, minY set as it is required by the MosaicDescriptor and indicated by its API doc.
                // But if we do it like that, we get lots of weird visual artifacts in the resulting mosaic.
                opImage = TranslateDescriptor.create(opImage,
                                                     (float) (tileRectangle.x >> level),
                                                     (float) (tileRectangle.y >> level),
                                                     Interpolation.getInstance(Interpolation.INTERP_NEAREST), null);


                logger.log(Level.parse(S2L2AConfig.LOG_SCENE), String.format("opImage added for level %d at (%d,%d)%n", level, opImage.getMinX(), opImage.getMinY()));
                tileImages.add(opImage);
            }

            if (tileImages.isEmpty()) {
                logger.warning("no tile images for mosaic");
                return null;
            }

            ImageLayout imageLayout = new ImageLayout();
            imageLayout.setMinX(0);
            imageLayout.setMinY(0);
            imageLayout.setTileWidth(DEFAULT_JAI_TILE_SIZE);
            imageLayout.setTileHeight(DEFAULT_JAI_TILE_SIZE);
            imageLayout.setTileGridXOffset(0);
            imageLayout.setTileGridYOffset(0);

            RenderedOp mosaicOp = MosaicDescriptor.create(tileImages.toArray(new RenderedImage[tileImages.size()]),
                                                          MosaicDescriptor.MOSAIC_TYPE_OVERLAY,
                                                          null, null, new double[][]{{1.0}}, new double[]{FILL_CODE_MOSAIC_BG},
                                                          new RenderingHints(JAI.KEY_IMAGE_LAYOUT, imageLayout));

            logger.fine(String.format("mosaicOp created for level %d at (%d,%d)%n", level, mosaicOp.getMinX(), mosaicOp.getMinY()));
            logger.fine(String.format("mosaicOp size: (%d,%d)%n", mosaicOp.getWidth(), mosaicOp.getHeight()));

            return mosaicOp;
        }
    }
}
