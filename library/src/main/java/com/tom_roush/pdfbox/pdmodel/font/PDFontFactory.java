/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tom_roush.pdfbox.pdmodel.font;

import android.util.Log;

import java.io.IOException;

import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.ResourceCache;

/**
 * Creates the appropriate font subtype based on information in the dictionary.
 * @author Ben Litchfield
 */
public final class PDFontFactory
{
    private PDFontFactory()
    {
    }

    /**
     * Creates a new PDFont instance with the appropriate subclass.
     *
     * @param dictionary a font dictionary
     * @return a PDFont instance, based on the SubType entry of the dictionary
     * @throws IOException if something goes wrong
     */
    public static PDFont createFont(COSDictionary dictionary) throws IOException
    {
        return createFont(dictionary, null);
    }

    /**
     * Creates a new PDFont instance with the appropriate subclass.
     *
     * @param dictionary a font dictionary
     * @param resourceCache resource cache, only useful for type 3 fonts, can be null
     * @return a PDFont instance, based on the SubType entry of the dictionary
     * @throws IOException if something goes wrong
     */
    public static PDFont createFont(COSDictionary dictionary, ResourceCache resourceCache) throws IOException
    {
        COSName type = dictionary.getCOSName(COSName.TYPE, COSName.FONT);
        if (!COSName.FONT.equals(type))
        {
            Log.e("PdfBox-Android", "Expected 'Font' dictionary but found '" + type.getName() + "'");
        }

        COSName subType = dictionary.getCOSName(COSName.SUBTYPE);
        if (COSName.TYPE1.equals(subType))
        {
            COSBase fd = dictionary.getDictionaryObject(COSName.FONT_DESC);
            if (fd instanceof COSDictionary && ((COSDictionary) fd).containsKey(COSName.FONT_FILE3))
            {
                return new PDType1CFont(dictionary);
            }
            return new PDType1Font(dictionary);
        }
        else if (COSName.MM_TYPE1.equals(subType))
        {
            COSBase fd = dictionary.getDictionaryObject(COSName.FONT_DESC);
            if (fd instanceof COSDictionary && ((COSDictionary) fd).containsKey(COSName.FONT_FILE3))
            {
                return new PDType1CFont(dictionary);
            }
            return new PDMMType1Font(dictionary);
        }
        else if (COSName.TRUE_TYPE.equals(subType))
        {
            return new PDTrueTypeFont(dictionary);
        }
        else if (COSName.TYPE3.equals(subType))
        {
            return new PDType3Font(dictionary, resourceCache);
        }
        else if (COSName.TYPE0.equals(subType))
        {
            return new PDType0Font(dictionary);
        }
        else if (COSName.CID_FONT_TYPE0.equals(subType))
        {
            throw new IOException("Type 0 descendant font not allowed");
        }
        else if (COSName.CID_FONT_TYPE2.equals(subType))
        {
            throw new IOException("Type 2 descendant font not allowed");
        }
        else
        {
            // assuming Type 1 font (see PDFBOX-1988) because it seems that Adobe Reader does this
            // however, we may need more sophisticated logic perhaps looking at the FontFile
            Log.w("PdfBox-Android", "Invalid font subtype '" + subType + "'");
            return new PDType1Font(dictionary);
        }
    }

    /**
     * Creates a new PDCIDFont instance with the appropriate subclass.
     *
     * @param dictionary descendant font dictionary
     * @return a PDCIDFont instance, based on the SubType entry of the dictionary
     * @throws IOException if something goes wrong
     */
    static PDCIDFont createDescendantFont(COSDictionary dictionary, PDType0Font parent)
        throws IOException
    {
        COSName type = dictionary.getCOSName(COSName.TYPE, COSName.FONT);
        if (!COSName.FONT.equals(type))
        {
            throw new IOException("Expected 'Font' dictionary but found '" + type.getName() + "'");
        }

        COSName subType = dictionary.getCOSName(COSName.SUBTYPE);
        if (COSName.CID_FONT_TYPE0.equals(subType))
        {
//            COSBase base = dictionary.getDictionaryObject(COSName.CIDSYSTEMINFO);
//            if (base instanceof COSDictionary)
//            {
//                PDCIDSystemInfo cidSystemInfo = new PDCIDSystemInfo((COSDictionary) base);
//                String collection = cidSystemInfo.getRegistry() + "-" + cidSystemInfo.getOrdering();
//                if (collection.equals("Adobe-GB1") || collection.equals("Adobe-CNS1") ||
//                        collection.equals("Adobe-Japan1") || collection.equals("Adobe-Korea1"))
//                {
//                    COSDictionary fd = (COSDictionary) dictionary.getDictionaryObject(COSName.FONT_DESC);
//                    if (FontMappers.instance().getCIDFontDefault(dictionary.getNameAsString(COSName.BASE_FONT), new PDFontDescriptor(fd), cidSystemInfo)==null) {
//                        Log.w("ceshi","？？？==="+collection);
//                        return new PDCIDFontType2(dictionary, parent);
//                    }
//                }
//            }
            return new PDCIDFontType0(dictionary, parent);
        }
        else if (COSName.CID_FONT_TYPE2.equals(subType))
        {
            return new PDCIDFontType2(dictionary, parent);
        }
        else
        {
            throw new IOException("Invalid font type: " + type);
        }
    }

    /**
     * Create a default font.
     *
     * @return a default font
     * @throws IOException if something goes wrong
     * @deprecated use {@link PDType1Font#HELVETICA}
     */
    @Deprecated
    public static PDFont createDefaultFont() throws IOException
    {
        return PDType1Font.HELVETICA;
    }
}
