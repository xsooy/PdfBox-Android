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
package com.tom_roush.pdfbox.pdmodel.graphics.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import com.tom_roush.harmony.javax.imageio.stream.ImageInputStream;
import com.tom_roush.harmony.javax.imageio.stream.MemoryCacheImageInputStream;
import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSNumber;
import com.tom_roush.pdfbox.filter.DecodeOptions;
import com.tom_roush.pdfbox.io.IOUtils;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColorSpace;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDICCBased;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDIndexed;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDSeparation;
import com.xsooy.Glable;
import com.xsooy.jpeg.JpegUtils;

/**
 * Reads a sampled image from a PDF file.
 * @author John Hewson
 */
final class SampledImageReader
{
    private SampledImageReader()
    {
    }

    /**
     * Returns an ARGB image filled with the given paint and using the given image as a mask.
     * @param paint the paint to fill the visible portions of the image with
     * @return a masked image filled with the given paint
     * @throws IOException if the image cannot be read
     * @throws IllegalStateException if the image is not a stencil.
     */
    public static Bitmap getStencilImage(PDImage pdImage, Paint paint) throws IOException
    {
        int width = pdImage.getWidth();
        int height = pdImage.getHeight();

        // compose to ARGB
        Bitmap masked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(masked);

        // draw the mask
        //g.drawImage(mask, 0, 0, null);

        // fill with paint using src-in
        //g.setComposite(AlphaComposite.SrcIn);
        g.drawRect(0, 0, width, height, paint);

        // set the alpha

        // avoid getting a Bitmap for the mask to lessen memory footprint.
        // Such masks are always bpc=1 and have no colorspace, but have a decode.
        // (see 8.9.6.2 Stencil Masking)
        ImageInputStream iis = null;
        try
        {
            iis = new MemoryCacheImageInputStream(pdImage.createInputStream());
            final float[] decode = getDecodeArray(pdImage);
            int value = decode[0] < decode[1] ? 1 : 0;
            int rowLen = width / 8;
            if (width % 8 > 0)
            {
                rowLen++;
            }
            byte[] buff = new byte[rowLen];
            int[] bank = new int[width];
            for (int y = 0; y < height; y++)
            {
                int x = 0;
                int readLen = iis.read(buff);
                if (readLen<rowLen) {
                    readLen+=iis.read(buff,readLen,rowLen-readLen);
                }
                masked.getPixels(bank,0,width,0,y,width,1);
                for (int r = 0; r < rowLen && r < readLen; r++)
                {
                    int byteValue = buff[r];
//                    Log.w("ceshi","0x"+Integer.toHexString(byteValue));
                    int mask = 128;
                    int shift = 7;
                    for (int i = 0; i < 8; i++)
                    {
                        int bit = (byteValue & mask) >> shift;
                        mask >>= 1;
                        --shift;
                        if (bit == value)
                        {
                            bank[x] = Color.TRANSPARENT;
//                            masked.setPixel(x, y, Color.TRANSPARENT);
                        }
                        x++;
                        if (x == width)
                        {
                            break;
                        }
                    }
                }
                if (readLen != rowLen)
                {
                    Log.w("PdfBox-Android", "premature EOF, image will be incomplete");
                    break;
                }
                masked.setPixels(bank,0,width,0,y,width,1);
            }
        }
        finally
        {
            if (iis != null)
            {
                iis.close();
            }
        }
        return masked;
    }

    /**
     * Returns the content of the given image as an AWT buffered image with an RGB color space.
     * If a color key mask is provided then an ARGB image is returned instead.
     * This method never returns null.
     * @param pdImage the image to read
     * @param colorKey an optional color key mask
     * @return content of this image as an RGB buffered image
     * @throws IOException if the image cannot be read
     */
    public static Bitmap getRGBImage(PDImage pdImage, COSArray colorKey) throws IOException
    {
        return getRGBImage(pdImage, null, 1, colorKey);
    }

    private static Rect clipRegion(PDImage pdImage, Rect region)
    {
        if (region == null)
        {
            return new Rect(0, 0, pdImage.getWidth(), pdImage.getHeight());
        }
        else
        {
            int x = Math.max(0, region.left);
            int y = Math.max(0, region.top);
            int width = Math.min(region.width(), pdImage.getWidth() - x);
            int height = Math.min(region.height(), pdImage.getHeight() - y);
            return new Rect(x, y, width, height);
        }
    }

    /**
     * Returns the content of the given image as a newly created Bitmap with an RGB color space.
     * If a color key mask is provided then an ARGB image is returned instead.
     * This method never returns null.
     * @param pdImage the image to read
     * @param region The region of the source image to get, or null if the entire image is needed.
     *               The actual region will be clipped to the dimensions of the source image.
     * @param subsampling The amount of rows and columns to advance for every output pixel, a value
     * of 1 meaning every pixel will be read. It must not be larger than the image width or height.
     * @param colorKey an optional color key mask
     * @return content of this image as an (A)RGB buffered image
     * @throws IOException if the image cannot be read
     */
    public static Bitmap getRGBImage(PDImage pdImage, Rect region, int subsampling,
        COSArray colorKey) throws IOException
    {
        if (pdImage.isEmpty())
        {
            throw new IOException("Image stream is empty");
        }
        Rect clipped = clipRegion(pdImage, region);

        // get parameters, they must be valid or have been repaired
        final PDColorSpace colorSpace = pdImage.getColorSpace();
        final int numComponents = colorSpace.getNumberOfComponents();
        final int width = (int) Math.ceil(clipped.width() / subsampling);
        final int height = (int) Math.ceil(clipped.height() / subsampling);
        final int bitsPerComponent = pdImage.getBitsPerComponent();

        if (width <= 0 || height <= 0 || pdImage.getWidth() <= 0 || pdImage.getHeight() <= 0)
        {
            throw new IOException("image width and height must be positive");
        }

        try
        {
            if (bitsPerComponent == 1 && colorKey == null && numComponents == 1)
            {
                return from1Bit(pdImage, clipped, subsampling, width, height);
            }

            //
            // An AWT raster must use 8/16/32 bits per component. Images with < 8bpc
            // will be unpacked into a byte-backed raster. Images with 16bpc will be reduced
            // in depth to 8bpc as they will be drawn to TYPE_INT_RGB images anyway. All code
            // in PDColorSpace#toRGBImage expects an 8-bit range, i.e. 0-255.
            final float[] defaultDecode = pdImage.getColorSpace().getDefaultDecode(8);
            final float[] decode = getDecodeArray(pdImage);
            if (pdImage.getSuffix() != null && subsampling == 1)
            {
//                Log.w("ceshi","suffix==="+pdImage.getSuffix());
//                Log.w("ceshi","colorSpace===="+pdImage.getColorSpace().getClass().getSimpleName());
//                Log.w("ceshi","numComponents===="+numComponents);
//                Log.w("ceshi","bitsPerComponent==="+bitsPerComponent+","+(colorKey == null));
                if (pdImage.getSuffix().equals("jpg")) {
                    if (pdImage.getColorSpace() instanceof PDDeviceCMYK) {
                        Bitmap raster = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        ByteBuffer buffer = ByteBuffer.allocate(raster.getRowBytes() * height);
                        final byte[] output = buffer.array();
                        InputStream inputStream = pdImage.createInputStream();

                        byte[] buff = new byte[inputStream.available()];
                        IOUtils.populateBuffer(inputStream,buff);

                        new JpegUtils().converDataToArray(buff,output);
                        raster.copyPixelsFromBuffer(buffer);

                        return pdImage.getColorSpace().toRGBImage(raster);

//                        InputStream inputStream = pdImage.createInputStream();
//                        byte[] buff = new byte[inputStream.available()];
//                        IOUtils.populateBuffer(inputStream,buff);
//                        return ((PDDeviceCMYK)pdImage.getColorSpace()).toRGBImage(new JpegUtils().converData(buff),width,height);
                    } else if (pdImage.getColorSpace() instanceof PDSeparation) {
                        Bitmap raster = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);

                        ByteBuffer buffer = ByteBuffer.allocate(raster.getRowBytes() * height);
                        final byte[] output = buffer.array();
                        InputStream inputStream = pdImage.createInputStream();

                        byte[] buff = new byte[inputStream.available()];
                        IOUtils.populateBuffer(inputStream,buff);

                        new JpegUtils().converDataToArray(buff,output);
                        raster.copyPixelsFromBuffer(buffer);

                        return pdImage.getColorSpace().toRGBImage(raster);
                    } else if (pdImage.getColorSpace() instanceof PDICCBased) {
                        Bitmap raster = numComponents==1?
                                Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8):
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                        InputStream inputStream = pdImage.createInputStream();

                        byte[] buff = new byte[inputStream.available()];
                        IOUtils.populateBuffer(inputStream,buff);
                        if (numComponents ==3) {
                            byte[] data = new JpegUtils().converData(buff);
                            final int[] banks = new int[width];
                            int index = 0;
                            for (int y=0;y<height;y++) {
                                for (int x=0;x<width;x++) {
                                    banks[x] = Color.argb(255,data[index*3]&0xff,data[index*3+1]&0xff,data[index*3+2]&0xff);
                                    index++;
                                }
                                raster.setPixels(banks,0,width,0,y,width,1);
                            }
                        } else {
                            ByteBuffer buffer = ByteBuffer.allocate(raster.getRowBytes() * height);
                            final byte[] output = buffer.array();
                            new JpegUtils().converDataToArray(buff,output);
                            raster.copyPixelsFromBuffer(buffer);
                        }

                        return pdImage.getColorSpace().toRGBImage(raster);
                    }

//                    Bitmap raster = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
//
//                    final int[] banks = new int[width];
//
//                    Log.w("ceshi","width:"+width);
//                    Log.w("ceshi","height:"+height);
//                    InputStream inputStream = pdImage.createInputStream();
//
//                    byte[] buff = new byte[inputStream.available()];
//                    IOUtils.populateBuffer(inputStream,buff);
//
//                    final byte[] data = new JpegUtils().converData(buff);
//                    Log.w("ceshi","data.length:"+data.length);
//
//                    int index = 0;
//                    for (int y=0;y<height;y++) {
//                        for (int x=0;x<width;x++) {
//                            banks[x] = Color.argb(255,data[index*3]&0xff,data[index*3+1]&0xff,data[index*3+2]&0xff);
//                            index++;
//                        }
//                        raster.setPixels(banks,0,width,0,y,width,1);
//                    }
//                    return raster;
                    return BitmapFactory.decodeStream(pdImage.createInputStream());
                } else if (pdImage.getSuffix().equals("jpx")){
                    InputStream inputStream = pdImage.createInputStream();
                    byte[] buff = new byte[inputStream.available()];
//                    Log.w("ceshi","input.available==="+inputStream.available());
                    IOUtils.populateBuffer(inputStream,buff);
                    int[] bank = new int[pdImage.getWidth()*pdImage.getHeight()];
                    int index = 0;
                    for (int h=0;h<pdImage.getHeight();h++) {
                        for (int w=0;w<pdImage.getWidth();w++) {
                            bank[index] = Color.argb(255,buff[index*3]&0xff,buff[index*3+1]&0xff,buff[index*3+2]&0xff);
                            index+=1;
                        }
                    }
                    return Bitmap.createBitmap(bank,pdImage.getWidth(),pdImage.getHeight(), Bitmap.Config.ARGB_8888);
                }
            }
            if (bitsPerComponent == 8 && colorKey == null && Arrays.equals(decode, defaultDecode))
            {
                // convert image, faster path for non-decoded, non-colormasked 8-bit images
                return from8bit(pdImage, clipped, subsampling, width, height);
            }
            Log.e("PdfBox-Android", "Trying to create other-bit image not supported");
            return fromAny(pdImage, colorKey, clipped, subsampling, width, height);
//            return from8bit(pdImage, clipped, subsampling, width, height);
        }
        catch (NegativeArraySizeException ex)
        {
            throw new IOException(ex);
        }
    }

//    public static WritableRaster getRawRaster(PDImage pdImage) throws IOException TODO: PdfBox-Android

//    private static void readRasterFromAny(PDImage pdImage, WritableRaster raster) TODO: PdfBox-Android

    private static Bitmap from1Bit(PDImage pdImage, Rect clipped, final int subsampling,
        final int width, final int height) throws IOException
    {
//        if (pdImage.getSuffix()!=null) {
//            Log.w("ceshi","suffix==="+pdImage.getSuffix());
//            Log.w("ceshi","colorSpace===="+pdImage.getColorSpace().getClass().getSimpleName());
//        }
        int currentSubsampling = subsampling;
        final PDColorSpace colorSpace = pdImage.getColorSpace();
        final float[] decode = getDecodeArray(pdImage);
        Bitmap raster = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
        ByteBuffer buffer = ByteBuffer.allocate(raster.getRowBytes() * height);
        raster.copyPixelsToBuffer(buffer);

        DecodeOptions options = new DecodeOptions(currentSubsampling);
        options.setSourceRegion(clipped);
        // read bit stream
        InputStream iis = null;
        try
        {
            // create stream
            iis = pdImage.createInputStream(options);

            final int inputWidth;
            final int startx;
            final int starty;
            final int scanWidth;
            final int scanHeight;
            if (options.isFilterSubsampled())
            {
                // Decode options were honored, and so there is no need for additional clipping or subsampling
                inputWidth = width;
                startx = 0;
                starty = 0;
                scanWidth = width;
                scanHeight = height;
                currentSubsampling = 1;
            }
            else
            {
                // Decode options not honored, so we need to clip and subsample ourselves.
                inputWidth = pdImage.getWidth();
                startx = clipped.left;
                starty = clipped.top;
                scanWidth = clipped.width();
                scanHeight = clipped.height();
            }
            final byte[] output = buffer.array();
            int idx = 0;

            // read stream byte per byte, invert pixel bits if necessary,
            // and then simply shift bits out to the left, detecting set bits via sign
            final boolean nosubsampling = currentSubsampling == 1;
            final int stride = (inputWidth + 7) / 8;
            final boolean isJBIG2 = !(pdImage.getSuffix() != null && pdImage.getSuffix().equals("jb2"));
            final int invert = isJBIG2 && (colorSpace instanceof PDIndexed || decode[0] < decode[1]) ? 0 : -1;
            final int endX = startx + scanWidth;
            final byte[] buff = new byte[stride];
            for (int y = 0; y < starty + scanHeight; y++)
            {
                int read = (int) IOUtils.populateBuffer(iis, buff);
                if (y >= starty && y % currentSubsampling == 0)
                {
                    int x = startx;
                    for (int r = x / 8; r < stride && r < read; r++)
                    {
                        int value = (buff[r] ^ invert) << (24 + (x & 7));
                        for (int count = Math.min(8 - (x & 7), endX - x); count > 0; x++, count--)
                        {
                            if (nosubsampling || x % currentSubsampling == 0)
                            {
                                if (value < 0)
                                {
                                    output[idx] = (byte) 255;
                                }
                                idx++;
                            }
                            value <<= 1;
                        }
                    }
                }
                if (read != stride)
                {
                    Log.w("PdfBox-Android", "premature EOF, image will be incomplete");
                    break;
                }
            }

            buffer.rewind();
            raster.copyPixelsFromBuffer(buffer);

            // use the color space to convert the image to RGB
            return colorSpace.toRGBImage(raster);
        }
        finally
        {
            if (iis != null)
            {
                iis.close();
            }
        }
    }

    // faster, 8-bit non-decoded, non-colormasked image conversion
    private static Bitmap from8bit(PDImage pdImage, Rect clipped, final int subsampling,
        final int width, final int height) throws IOException
    {
        Log.w("ceshi","from8bit");
        int currentSubsampling = subsampling;
        DecodeOptions options = new DecodeOptions(currentSubsampling);
        options.setSourceRegion(clipped);
        InputStream input = pdImage.createInputStream(options);
        try
        {
            final int inputWidth;
            int startx;
            int starty;
            final int scanWidth;
            final int scanHeight;
            if (options.isFilterSubsampled())
            {
                // Decode options were honored, and so there is no need for additional clipping or subsampling
                inputWidth = width;
                startx = 0;
                starty = 0;
                scanWidth = width;
                scanHeight = height;
                currentSubsampling = 1;
            }
            else
            {
                // Decode options not honored, so we need to clip and subsample ourselves.
                inputWidth = pdImage.getWidth();
                startx = clipped.left;
                starty = clipped.top;
                scanWidth = clipped.width();
                scanHeight = clipped.height();
            }
            final int numComponents = pdImage.getColorSpace().getNumberOfComponents();
            if (startx == 0 && starty == 0 && scanWidth == width && scanHeight == height)
            {
                Log.w("ceshi","input==="+input.available()+","+width+","+height);
                Log.w("ceshi","input==="+numComponents);
                // we just need to copy all sample data, then convert to RGB image.
                if (numComponents == 1) {
                    Bitmap raster = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
                    ByteBuffer buffer = ByteBuffer.allocate(raster.getRowBytes() * height);
                    final byte[] output = buffer.array();
                    if(input.read(output)>0) {
                        raster.copyPixelsFromBuffer(buffer);
                    }

//                    Bitmap nn = pdImage.getColorSpace().toRGBImage(raster);
//                    File f = new File("/storage/emulated/0/Android/data/com.tom_roush.pdfbox.sample/files/temp_"+ Glable.jj +".png");
//                    Glable.jj++;
//                    if (f.exists()) {
//                        f.delete();
//                    }
//                    try {
//                        FileOutputStream out = new FileOutputStream(f);
//                        nn.compress(Bitmap.CompressFormat.JPEG, 80, out);
//                        out.flush();
//                        out.close();
//                    } catch (FileNotFoundException e) {
//                        e.printStackTrace();
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                    return nn;
                    return pdImage.getColorSpace().toRGBImage(raster);
//                    return raster;
                } else if (numComponents == 4) {
                    Bitmap raster = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    ByteBuffer buffer = ByteBuffer.allocate(raster.getRowBytes() * height);
                    final byte[] output = buffer.array();
                    if(input.read(output)>0) {
                        raster.copyPixelsFromBuffer(buffer);
                    }

                    return pdImage.getColorSpace().toRGBImage(raster);
                }
                return createBitmapFromRawStream(input, inputWidth, numComponents, currentSubsampling);
            }
            else
            {
                Log.w("ceshi","走到这里了？");
                Bitmap origin = createBitmapFromRawStream(input, inputWidth, numComponents,
                    currentSubsampling);
                if (currentSubsampling > 1)
                {
                    startx /= currentSubsampling;
                    starty /= currentSubsampling;
                }
                return Bitmap.createBitmap(origin, startx, starty, width, height);
            }
        }
        finally
        {
            IOUtils.closeQuietly(input);
        }
    }

    private static Bitmap createBitmapFromRawStream(InputStream input, int originalWidth, int numComponents,
        int sampleSize) throws IOException
    {
        byte[] bytes = IOUtils.toByteArray(input);
        int originalHeight = bytes.length / numComponents / originalWidth;
        if (numComponents == 1)
        {
            byte[] result = new byte[originalWidth * originalHeight * 4];
            for (int i = originalWidth * originalHeight - 1; i >= 0; i--)
            {
                int to = i * 4;
                result[to + 3] = bytes[i];
                result[to] = bytes[i];
                result[to + 1] = bytes[i];
                result[to + 2] = bytes[i];
            }
            bytes = result;
        }
        else if (numComponents == 3)
        {
            byte[] result = new byte[originalWidth * originalHeight * 4];
            for (int i = originalWidth * originalHeight - 1; i >= 0; i--)
            {
                int to = i * 4;
                int from = i * 3;
                result[to + 3] = (byte)255;
                result[to] = bytes[from];
                result[to + 1] = bytes[from + 1];
                result[to + 2] = bytes[from + 2];
            }
            bytes = result;
        }
        Bitmap bitmap = Bitmap.createBitmap(originalWidth, originalHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes));
        if (sampleSize > 1)
        {
            int width = originalWidth / sampleSize;
            int height = originalHeight / sampleSize;
            bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
        }
        return bitmap;
    }

    // slower, general-purpose image conversion from any image format
//    private static BufferedImage fromAny(PDImage pdImage, WritableRaster raster, COSArray colorKey, Rectangle clipped,
//        final int subsampling, final int width, final int height) TODO: Pdfbox-Android
    private static Bitmap fromAny(PDImage pdImage,
//                                  WritableRaster raster,
                                  COSArray colorKey, Rect clipped,
                                  final int subsampling, final int width, final int height)
            throws IOException
    {
        int currentSubsampling = subsampling;
        final PDColorSpace colorSpace = pdImage.getColorSpace();
        final int numComponents = colorSpace.getNumberOfComponents();
        final int bitsPerComponent = pdImage.getBitsPerComponent();
        final float[] decode = getDecodeArray(pdImage);

        DecodeOptions options = new DecodeOptions(currentSubsampling);
        options.setSourceRegion(clipped);
        // read bit stream

        try {
            ImageInputStream input = new MemoryCacheImageInputStream(pdImage.createInputStream(options));
            final int inputWidth;
            final int startx;
            final int starty;
            final int scanWidth;
            final int scanHeight;
            if (options.isFilterSubsampled()) {
                // Decode options were honored, and so there is no need for additional clipping or subsampling
                inputWidth = width;
                startx = 0;
                starty = 0;
                scanWidth = width;
                scanHeight = height;
                currentSubsampling = 1;
            } else {
                // Decode options not honored, so we need to clip and subsample ourselves.
                inputWidth = pdImage.getWidth();
                startx = clipped.left;
                starty = clipped.top;
                scanWidth = clipped.width();
                scanHeight = clipped.height();
            }
            final float sampleMax = (float) Math.pow(2, bitsPerComponent) - 1f;
            final boolean isIndexed = colorSpace instanceof PDIndexed;

            // init color key mask
            float[] colorKeyRanges = null;
//            BufferedImage colorKeyMask = null;
            Bitmap colorKeyMask = null;
            if (colorKey != null) {
                colorKeyRanges = colorKey.toFloatArray();
                colorKeyMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
            }

            // calculate row padding
            int padding = 0;
            if (inputWidth * numComponents * bitsPerComponent % 8 > 0) {
                padding = 8 - (inputWidth * numComponents * bitsPerComponent % 8);
            }

            // read stream
            int[] banks = new int[width * height];
            byte[] srcColorValues = new byte[numComponents];
            byte[] alpha = new byte[1];
//            byte[] tempBytes = new byte[numComponents * inputWidth];
            for (int y = 0; y < starty + scanHeight; y++) {
                for (int x = 0; x < startx + scanWidth; x++) {
                    boolean isMasked = true;
                    for (int c = 0; c < numComponents; c++) {
                        int value = (int) input.readBits(bitsPerComponent);

                        // color key mask requires values before they are decoded
                        if (colorKeyRanges != null) {
                            isMasked &= value >= colorKeyRanges[c * 2] &&
                                    value <= colorKeyRanges[c * 2 + 1];
                        }

                        // decode array
                        final float dMin = decode[c * 2];
                        final float dMax = decode[(c * 2) + 1];

                        // interpolate to domain
                        float output = dMin + (value * ((dMax - dMin) / sampleMax));

                        if (isIndexed) {
                            // indexed color spaces get the raw value, because the TYPE_BYTE
                            // below cannot be reversed by the color space without it having
                            // knowledge of the number of bits per component
                            srcColorValues[c] = (byte) Math.round(output);

                        } else {
                            // interpolate to TYPE_BYTE
                            int outputByte = Math.round(((output - Math.min(dMin, dMax)) /
                                    Math.abs(dMax - dMin)) * 255f);
                            srcColorValues[c] = (byte) outputByte;
                        }
                    }
                    // only write to output if within requested region and subsample.
                    if (x >= startx && y >= starty && x % currentSubsampling == 0 && y % currentSubsampling == 0) {
                        if (numComponents == 1) {
                            banks[(y - starty) * scanWidth + (x - startx)] = Color.argb(srcColorValues[0],srcColorValues[0],srcColorValues[0],srcColorValues[0]);
                        } else {
                            banks[(y - starty) * scanWidth + (x - startx)] = Color.argb(isMasked ? 255 : 0, srcColorValues[0], srcColorValues[1], srcColorValues[2]);
                        }
//                        raster.setDataElements((x - startx) / currentSubsampling, (y - starty) / currentSubsampling, srcColorValues);
//
//                        // set alpha channel in color key mask, if any
                        if (colorKeyMask != null)
                        {
                            alpha[0] = (byte)(isMasked ? 255 : 0);
                            colorKeyMask.setPixel((x - startx) / currentSubsampling, (y - starty) / currentSubsampling,(int)alpha[0]);
                        }
                    }
                }
                // rows are padded to the nearest byte
                input.readBits(padding);
            }

            Bitmap raster;
            if (pdImage.getColorSpace() instanceof PDIndexed) {
                raster = Bitmap.createBitmap(width,height,Bitmap.Config.ALPHA_8);
                raster.setPixels(banks, 0, width, 0 ,0, width, height);
                raster = ((PDIndexed)pdImage.getColorSpace()).toRGBImage(raster);
            } else {
                raster = Bitmap.createBitmap(width, height,Bitmap.Config.ARGB_8888);
                raster.setPixels(banks, 0, width, 0 ,0, width, height);
            }
            if (colorKeyMask != null)
            {
                return applyColorKeyMask(raster, colorKeyMask);
            }
            return raster;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }


    // color key mask: RGB + Binary -> ARGB
//    private static BufferedImage applyColorKeyMask(BufferedImage image, BufferedImage mask) TODO: PdfBox-Android
    private static Bitmap applyColorKeyMask(Bitmap image, Bitmap mask)
    {
        int width = image.getWidth();
        int height = image.getHeight();

        // compose to ARGB
        Bitmap masked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

//        WritableRaster src = image.getRaster();
//        WritableRaster dest = masked.getRaster();
//        WritableRaster alpha = mask.getRaster();

        int[] src = new int[width];
        int[] alpha = new int[width];
        int[] dest = new int[width];
        for (int y = 0; y < height; y++)
        {
            image.getPixels(src,0,width,0 , y, width,1);
            mask.getPixels(alpha,0,width,0, y, width,1);
            for (int x = 0; x < width; x++)
            {
                dest[x] = Color.argb(255-Color.alpha(alpha[x]),Color.red(src[x]),Color.green(src[x]),Color.blue(src[x]));
            }
            masked.setPixels(dest,0,width,0,y,width,1);
        }

        return masked;
    }

    // gets decode array from dictionary or returns default
    private static float[] getDecodeArray(PDImage pdImage) throws IOException
    {
        final COSArray cosDecode = pdImage.getDecode();
        float[] decode = null;

        if (cosDecode != null)
        {
            int numberOfComponents = pdImage.getColorSpace().getNumberOfComponents();
            if (cosDecode.size() != numberOfComponents * 2)
            {
                if (pdImage.isStencil() && cosDecode.size() >= 2
                    && cosDecode.get(0) instanceof COSNumber
                    && cosDecode.get(1) instanceof COSNumber)
                {
                    float decode0 = ((COSNumber) cosDecode.get(0)).floatValue();
                    float decode1 = ((COSNumber) cosDecode.get(1)).floatValue();
                    if (decode0 >= 0 && decode0 <= 1 && decode1 >= 0 && decode1 <= 1)
                    {
                        Log.w("PdfBox-Android", "decode array " + cosDecode
                            + " not compatible with color space, using the first two entries");
                        return new float[]
                            {
                                decode0, decode1
                            };
                    }
                }
                Log.e("PdfBox-Android", "decode array " + cosDecode
                    + " not compatible with color space, using default");
            }
            else
            {
                decode = cosDecode.toFloatArray();
            }
        }

        // use color space default
        if (decode == null)
        {
            return pdImage.getColorSpace().getDefaultDecode(pdImage.getBitsPerComponent());
        }

        return decode;
    }
}
