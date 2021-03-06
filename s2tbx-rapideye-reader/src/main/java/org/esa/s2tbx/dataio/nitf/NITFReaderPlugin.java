/*
 * Copyright (C) 2014-2015 CS SI
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 *  with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.s2tbx.dataio.nitf;

import org.esa.snap.framework.dataio.DecodeQualification;
import org.esa.snap.framework.dataio.ProductReader;
import org.esa.snap.framework.dataio.ProductReaderPlugIn;
import org.esa.snap.util.io.SnapFileFilter;

import java.io.File;
import java.util.Locale;

/**
 * Reader plugin class for NITF images.
 *
 * @author Cosmin Cara
 */
public class NITFReaderPlugin implements ProductReaderPlugIn {

    @Override
    public DecodeQualification getDecodeQualification(Object input) {
        DecodeQualification retVal = DecodeQualification.UNABLE;
        File inputFile = input instanceof File ? (File)input : new File(input.toString());
        if (inputFile.getName().endsWith(NITFConstants.NTF_EXTENSION)) {
            retVal = DecodeQualification.INTENDED;
        }
        return retVal;
    }

    @Override
    public Class[] getInputTypes() {
        return NITFConstants.READER_INPUT_TYPES;
    }

    @Override
    public ProductReader createReaderInstance() {
        return new NITFReader(this);
    }

    @Override
    public String[] getFormatNames() {
        return NITFConstants.FORMAT_NAMES;
    }

    @Override
    public String[] getDefaultFileExtensions() {
        return new String[] { NITFConstants.NTF_EXTENSION };
    }

    @Override
    public String getDescription(Locale locale) {
        return NITFConstants.NITF_DESCRIPTION;
    }

    @Override
    public SnapFileFilter getProductFileFilter() {
        return new SnapFileFilter(getFormatNames()[0], getDefaultFileExtensions(), getDescription(Locale.getDefault()));
    }
}
