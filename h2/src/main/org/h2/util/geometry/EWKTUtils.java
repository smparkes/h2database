/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.util.geometry;

import static org.h2.util.geometry.GeometryUtils.DIMENSION_SYSTEM_XYM;
import static org.h2.util.geometry.GeometryUtils.DIMENSION_SYSTEM_XYZ;
import static org.h2.util.geometry.GeometryUtils.DIMENSION_SYSTEM_XYZM;
import static org.h2.util.geometry.GeometryUtils.GEOMETRY_COLLECTION;
import static org.h2.util.geometry.GeometryUtils.LINE_STRING;
import static org.h2.util.geometry.GeometryUtils.M;
import static org.h2.util.geometry.GeometryUtils.MULTI_LINE_STRING;
import static org.h2.util.geometry.GeometryUtils.MULTI_POINT;
import static org.h2.util.geometry.GeometryUtils.MULTI_POLYGON;
import static org.h2.util.geometry.GeometryUtils.POINT;
import static org.h2.util.geometry.GeometryUtils.POLYGON;
import static org.h2.util.geometry.GeometryUtils.X;
import static org.h2.util.geometry.GeometryUtils.Y;
import static org.h2.util.geometry.GeometryUtils.Z;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

import org.h2.util.StringUtils;
import org.h2.util.geometry.EWKBUtils.EWKBTarget;
import org.h2.util.geometry.GeometryUtils.DimensionSystemTarget;
import org.h2.util.geometry.GeometryUtils.Target;

/**
 * EWKT format support for GEOMETRY data type.
 *
 * <p>
 * This class provides limited support of EWKT. EWKT is based on Well-known Text
 * Representation (WKT) from OGC 06-103r4 and includes additional <a href=
 * "https://postgis.net/docs/using_postgis_dbmanagement.html#EWKB_EWKT">PostGIS
 * extensions</a>. SRID support from EWKT is implemented.
 * </p>
 */
public final class EWKTUtils {

    /**
     * Converter output target that writes a EWKT.
     */
    public static final class EWKTTarget extends Target {

        private final StringBuilder output;

        private final int dimensionSystem;

        private int type;

        private boolean inMulti;

        /**
         * Creates a new EWKT output target.
         *
         * @param output
         *            output stream
         * @param dimensionSystem
         *            dimension system to use
         */
        public EWKTTarget(StringBuilder output, int dimensionSystem) {
            this.output = output;
            this.dimensionSystem = dimensionSystem;
        }

        @Override
        protected void init(int srid) {
            if (srid != 0) {
                output.append("SRID=").append(srid).append(';');
            }
        }

        @Override
        protected void startPoint() {
            writeHeader(POINT);
        }

        @Override
        protected void startLineString(int numPoints) {
            writeHeader(LINE_STRING);
            if (numPoints == 0) {
                output.append("EMPTY");
            }
        }

        @Override
        protected void startPolygon(int numInner, int numPoints) {
            writeHeader(POLYGON);
            if (numPoints == 0) {
                output.append("EMPTY");
            } else {
                output.append('(');
            }
        }

        @Override
        protected void startPolygonInner(int numInner) {
            output.append(numInner > 0 ? ", " : ", EMPTY");
        }

        @Override
        protected void endNonEmptyPolygon() {
            output.append(')');
        }

        @Override
        protected void startCollection(int type, int numItems) {
            writeHeader(type);
            if (numItems == 0) {
                output.append("EMPTY");
            }
            if (type != GEOMETRY_COLLECTION) {
                inMulti = true;
            }
        }

        private void writeHeader(int type) {
            this.type = type;
            if (inMulti) {
                return;
            }
            switch (type) {
            case POINT:
                output.append("POINT");
                break;
            case LINE_STRING:
                output.append("LINESTRING");
                break;
            case POLYGON:
                output.append("POLYGON");
                break;
            case MULTI_POINT:
                output.append("MULTIPOINT");
                break;
            case MULTI_LINE_STRING:
                output.append("MULTILINESTRING");
                break;
            case MULTI_POLYGON:
                output.append("MULTIPOLYGON");
                break;
            case GEOMETRY_COLLECTION:
                output.append("GEOMETRYCOLLECTION");
                break;
            default:
                throw new IllegalArgumentException();
            }
            switch (dimensionSystem) {
            case DIMENSION_SYSTEM_XYZ:
                output.append(" Z");
                break;
            case DIMENSION_SYSTEM_XYM:
                output.append(" M");
                break;
            case DIMENSION_SYSTEM_XYZM:
                output.append(" ZM");
            }
            output.append(' ');
        }

        @Override
        protected Target startCollectionItem(int index, int total) {
            if (index == 0) {
                output.append('(');
            } else {
                output.append(", ");
            }
            return this;
        }

        @Override
        protected void endCollectionItem(Target target, int index, int total) {
            if (index + 1 == total) {
                output.append(')');
            }
        }

        @Override
        protected void endCollection(int type) {
            if (type != GEOMETRY_COLLECTION) {
                inMulti = false;
            }
        }

        @Override
        protected void addCoordinate(double x, double y, double z, double m, int index, int total) {
            if (type == POINT && Double.isNaN(x) && Double.isNaN(y) && Double.isNaN(z) && Double.isNaN(m)) {
                output.append("EMPTY");
                return;
            }
            if (index == 0) {
                output.append('(');
            } else {
                output.append(", ");
            }
            writeDouble(x);
            output.append(' ');
            writeDouble(y);
            if ((dimensionSystem & DIMENSION_SYSTEM_XYZ) != 0) {
                output.append(' ');
                writeDouble(z);
            }
            if ((dimensionSystem & DIMENSION_SYSTEM_XYM) != 0) {
                output.append(' ');
                writeDouble(m);
            }
            if (index + 1 == total) {
                output.append(')');
            }
        }

        private void writeDouble(double v) {
            String s = Double.toString(GeometryUtils.checkFinite(v));
            if (s.endsWith(".0")) {
                output.append(s, 0, s.length() - 2);
            } else {
                int idx = s.indexOf(".0E");
                if (idx < 0) {
                    output.append(s);
                } else {
                    output.append(s, 0, idx).append(s, idx + 2, s.length());
                }
            }
        }

    }

    /**
     * Helper source object for EWKT reading.
     */
    private static final class EWKTSource {
        private final String ewkt;

        private int offset;

        EWKTSource(String ewkt) {
            this.ewkt = ewkt;
        }

        int readSRID() {
            int srid;
            if (ewkt.startsWith("SRID=")) {
                int idx = ewkt.indexOf(';', 5);
                srid = Integer.parseInt(ewkt.substring(5, idx));
                offset = idx + 1;
            } else {
                srid = 0;
            }
            return srid;
        }

        void read(char symbol) {
            skipWS();
            int len = ewkt.length();
            if (offset >= len) {
                throw new IllegalArgumentException();
            }
            if (ewkt.charAt(offset) != symbol) {
                throw new IllegalArgumentException();
            }
            offset++;
        }

        boolean readEmpty(boolean empty) {
            if (empty) {
                return true;
            }
            skipWS();
            int len = ewkt.length();
            if (offset >= len) {
                throw new IllegalArgumentException();
            }
            if (ewkt.charAt(offset) == '(') {
                offset++;
                return false;
            }
            if (!readWord().equals("EMPTY")) {
                throw new IllegalArgumentException();
            }
            return true;
        }

        String readWord() {
            return readWordImpl(true);
        }

        String tryReadWord() {
            return readWordImpl(false);
        }

        private String readWordImpl(boolean required) {
            skipWS();
            int len = ewkt.length();
            if (offset >= len) {
                if (required) {
                    throw new IllegalArgumentException();
                } else {
                    return null;
                }
            }
            char ch = ewkt.charAt(offset);
            if (!isLatinLetter(ch)) {
                if (required) {
                    throw new IllegalArgumentException();
                } else {
                    return null;
                }
            }
            int start = offset++;
            while (offset < len && isLatinLetter(ch = ewkt.charAt(offset))) {
                offset++;
            }
            if (offset < len) {
                if (ch > ' ' && ch != '(' && ch != ')' && ch != ',') {
                    throw new IllegalArgumentException();
                }
            }
            return StringUtils.toUpperEnglish(ewkt.substring(start, offset));
        }

        private static boolean isLatinLetter(char ch) {
            return ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z';
        }

        public boolean hasCoordinate() {
            skipWS();
            if (offset >= ewkt.length()) {
                return false;
            }
            return isNumberStart(ewkt.charAt(offset));
        }

        public double readCoordinate() {
            skipWS();
            int len = ewkt.length();
            if (offset >= len) {
                throw new IllegalArgumentException();
            }
            char ch = ewkt.charAt(offset);
            if (!isNumberStart(ch)) {
                throw new IllegalArgumentException();
            }
            int start = offset++;
            while (offset < len && isNumberPart(ch = ewkt.charAt(offset))) {
                offset++;
            }
            if (offset < len) {
                if (ch > ' ' && ch != ')' && ch != ',') {
                    throw new IllegalArgumentException();
                }
            }
            Double d = Double.parseDouble(ewkt.substring(start, offset));
            return d == 0 ? 0 : d;
        }

        private static boolean isNumberStart(char ch) {
            if (ch >= '0' && ch <= '9') {
                return true;
            }
            switch (ch) {
            case '+':
            case '-':
            case '.':
                return true;
            default:
                return false;
            }
        }

        private static boolean isNumberPart(char ch) {
            if (ch >= '0' && ch <= '9') {
                return true;
            }
            switch (ch) {
            case '+':
            case '-':
            case '.':
            case 'E':
            case 'e':
                return true;
            default:
                return false;
            }
        }

        public boolean hasMoreCoordinates() {
            skipWS();
            if (offset >= ewkt.length()) {
                throw new IllegalArgumentException();
            }
            switch (ewkt.charAt(offset)) {
            case ',':
                offset++;
                return true;
            case ')':
                offset++;
                return false;
            default:
                throw new IllegalArgumentException();
            }
        }

        boolean hasData() {
            skipWS();
            return offset < ewkt.length();
        }

        int getItemCount() {
            int result = 1;
            int offset = this.offset, level = 0, len = ewkt.length();
            while (offset < len) {
                switch (ewkt.charAt(offset++)) {
                case ',':
                    if (level == 0) {
                        result++;
                    }
                    break;
                case '(':
                    level++;
                    break;
                case ')':
                    if (--level < 0) {
                        return result;
                    }
                }
            }
            throw new IllegalArgumentException();
        }

        private void skipWS() {
            for (int len = ewkt.length(); offset < len && ewkt.charAt(offset) <= ' '; offset++) {
            }
        }

        @Override
        public String toString() {
            return new StringBuilder(ewkt.length() + 3).append(ewkt, 0, offset).append("<*>")
                    .append(ewkt, offset, ewkt.length()).toString();
        }

    }

    /**
     * Converts EWKB to EWKT.
     *
     * @param ewkb
     *            source EWKB
     * @return EWKT representation
     */
    public static String ewkb2ewkt(byte[] ewkb) {
        // Determine dimension system first
        DimensionSystemTarget dimensionTarget = new DimensionSystemTarget();
        EWKBUtils.parseEWKB(ewkb, dimensionTarget);
        // Write an EWKT
        return ewkb2ewkt(ewkb, dimensionTarget.getDimensionSystem());
    }

    /**
     * Converts EWKB to EWKT.
     *
     * @param ewkb
     *            source EWKB
     * @param dimension
     *            dimension system
     * @return EWKT representation
     */
    public static String ewkb2ewkt(byte[] ewkb, int dimensionSystem) {
        StringBuilder output = new StringBuilder();
        EWKTTarget target = new EWKTTarget(output, dimensionSystem);
        EWKBUtils.parseEWKB(ewkb, target);
        return output.toString();
    }

    /**
     * Converts EWKT to EWKB.
     *
     * @param ewkt
     *            source EWKT
     * @return EWKB representation
     */
    public static byte[] ewkt2ewkb(String ewkt) {
        // Determine dimension system first
        DimensionSystemTarget dimensionTarget = new DimensionSystemTarget();
        parseEWKT(ewkt, dimensionTarget);
        // Write an EWKB
        return ewkt2ewkb(ewkt, dimensionTarget.getDimensionSystem());
    }

    /**
     * Converts EWKT to EWKB.
     *
     * @param ewkt
     *            source EWKT
     * @param dimension
     *            dimension system
     * @return EWKB representation
     */
    public static byte[] ewkt2ewkb(String ewkt, int dimensionSystem) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EWKBTarget target = new EWKBTarget(output, dimensionSystem);
        parseEWKT(ewkt, target);
        return output.toByteArray();
    }

    /**
     * Parses a EWKB.
     *
     * @param source
     *            source EWKT
     * @param target
     *            output target
     */
    public static void parseEWKT(String ewkt, Target target) {
        parseEWKT(new EWKTSource(ewkt), target, 0, false, false);
    }

    /**
     * Parses a EWKB.
     *
     * @param source
     *            EWKT source
     * @param target
     *            output target
     * @param parentType
     *            type of parent geometry collection, or 0 for the root geometry
     * @param useZ
     *            parent geometry uses dimension Z
     * @param useM
     *            parent geometry uses dimension M
     */
    private static void parseEWKT(EWKTSource source, Target target, int parentType, boolean useZ, boolean useM) {
        if (parentType == 0) {
            target.init(source.readSRID());
        }
        String type;
        boolean empty = false;
        switch (parentType) {
        default: {
            type = source.readWord();
            if (type.endsWith("M")) {
                useM = true;
                if (type.endsWith("ZM")) {
                    useZ = true;
                    type = type.substring(0, type.length() - 2);
                } else {
                    type = type.substring(0, type.length() - 1);
                }
            } else if (type.endsWith("Z")) {
                useZ = true;
                type = type.substring(0, type.length() - 1);
            } else {
                String s = source.tryReadWord();
                if (s != null) {
                    switch (s) {
                    case "Z":
                        useZ = true;
                        break;
                    case "M":
                        useM = true;
                        break;
                    case "ZM":
                        useZ = useM = true;
                        break;
                    case "EMPTY":
                        empty = true;
                        break;
                    default:
                        throw new IllegalArgumentException();
                    }
                }
            }
            break;
        }
        case MULTI_POINT:
            type = "POINT";
            break;
        case MULTI_LINE_STRING:
            type = "LINESTRING";
            break;
        case MULTI_POLYGON:
            type = "POLYGON";
            break;
        }
        switch (type) {
        case "POINT":
            if (parentType != 0 && parentType != MULTI_POINT && parentType != GEOMETRY_COLLECTION) {
                throw new IllegalArgumentException();
            }
            empty = source.readEmpty(empty);
            target.startPoint();
            if (empty) {
                target.addCoordinate(Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 1);
            } else {
                addCoordinate(source, target, useZ, useM, 0, 1);
                source.read(')');
            }
            break;
        case "LINESTRING": {
            if (parentType != 0 && parentType != MULTI_LINE_STRING && parentType != GEOMETRY_COLLECTION) {
                throw new IllegalArgumentException();
            }
            empty = source.readEmpty(empty);
            if (empty) {
                target.startLineString(0);
            } else {
                ArrayList<double[]> coordinates = new ArrayList<>();
                do {
                    coordinates.add(readCoordinate(source, useZ, useM));
                } while (source.hasMoreCoordinates());
                int numPoints = coordinates.size();
                if (numPoints < 0 || numPoints == 1) {
                    throw new IllegalArgumentException();
                }
                target.startLineString(numPoints);
                for (int i = 0; i < numPoints; i++) {
                    double[] c = coordinates.get(i);
                    target.addCoordinate(c[X], c[Y], c[Z], c[M], i, numPoints);
                }
            }
            break;
        }
        case "POLYGON": {
            if (parentType != 0 && parentType != MULTI_POLYGON && parentType != GEOMETRY_COLLECTION) {
                throw new IllegalArgumentException();
            }
            empty = source.readEmpty(empty);
            if (empty) {
                target.startPolygon(0, 0);
            } else {
                ArrayList<double[]> outer = readRing(source, useZ, useM);
                ArrayList<ArrayList<double[]>> inner = new ArrayList<>();
                while (source.hasMoreCoordinates()) {
                    inner.add(readRing(source, useZ, useM));
                }
                int numInner = inner.size();
                int size = outer.size();
                // Size may be 0 (EMPTY) or 4+
                if (size >= 1 && size <= 3) {
                    throw new IllegalArgumentException();
                }
                if (size == 0 && numInner > 0) {
                    throw new IllegalArgumentException();
                }
                target.startPolygon(numInner, size);
                if (size > 0) {
                    addRing(outer, target, useZ, useM);
                    for (int i = 0; i < numInner; i++) {
                        ArrayList<double[]> ring = inner.get(i);
                        size = ring.size();
                        // Size may be 0 (EMPTY) or 4+
                        if (size >= 1 && size <= 3) {
                            throw new IllegalArgumentException();
                        }
                        target.startPolygonInner(size);
                        addRing(ring, target, useZ, useM);
                    }
                    target.endNonEmptyPolygon();
                }
            }
            break;
        }
        case "MULTIPOINT":
            parseCollection(source, target, MULTI_POINT, parentType, empty, useZ, useM);
            break;
        case "MULTILINESTRING":
            parseCollection(source, target, MULTI_LINE_STRING, parentType, empty, useZ, useM);
            break;
        case "MULTIPOLYGON":
            parseCollection(source, target, MULTI_POLYGON, parentType, empty, useZ, useM);
            break;
        case "GEOMETRYCOLLECTION":
            parseCollection(source, target, GEOMETRY_COLLECTION, parentType, empty, useZ, useM);
            break;
        default:
            throw new IllegalArgumentException();
        }
        if (parentType == 0 && source.hasData()) {
            throw new IllegalArgumentException();
        }
    }

    private static void parseCollection(EWKTSource source, Target target, int type, int parentType, boolean empty,
            boolean useZ, boolean useM) {
        if (parentType != 0 && parentType != GEOMETRY_COLLECTION) {
            throw new IllegalArgumentException();
        }
        if (source.readEmpty(empty)) {
            target.startCollection(type, 0);
        } else {
            if (type == MULTI_POINT && source.hasCoordinate()) {
                parseMultiPointAlternative(source, target, useZ, useM);
            } else {
                int numItems = source.getItemCount();
                target.startCollection(type, numItems);
                for (int i = 0; i < numItems; i++) {
                    if (i > 0) {
                        source.read(',');
                    }
                    Target innerTarget = target.startCollectionItem(i, numItems);
                    parseEWKT(source, innerTarget, type, useZ, useM);
                    target.endCollectionItem(innerTarget, i, numItems);
                }
                source.read(')');
            }
        }
        target.endCollection(type);
    }

    private static void parseMultiPointAlternative(EWKTSource source, Target target, boolean useZ, boolean useM) {
        // Special case for MULTIPOINT (1 2, 3 4)
        ArrayList<double[]> points = new ArrayList<>();
        do {
            points.add(readCoordinate(source, useZ, useM));
        } while (source.hasMoreCoordinates());
        int numItems = points.size();
        target.startCollection(MULTI_POINT, numItems);
        for (int i = 0; i < points.size(); i++) {
            Target innerTarget = target.startCollectionItem(i, numItems);
            target.startPoint();
            double[] c = points.get(i);
            target.addCoordinate(c[X], c[Y], c[Z], c[M], 0, 1);
            target.endCollectionItem(innerTarget, i, numItems);
        }
    }

    private static ArrayList<double[]> readRing(EWKTSource source, boolean useZ, boolean useM) {
        if (source.readEmpty(false)) {
            return new ArrayList<>(0);
        }
        ArrayList<double[]> result = new ArrayList<>();
        double[] c = readCoordinate(source, useZ, useM);
        double startX = c[X], startY = c[Y];
        result.add(c);
        while (source.hasMoreCoordinates()) {
            result.add(readCoordinate(source, useZ, useM));
        }
        int size = result.size();
        if (size < 4) {
            throw new IllegalArgumentException();
        }
        c = result.get(size - 1);
        double endX = c[X], endY = c[Y];
        /*
         * TODO OGC 06-103r4 determines points as equal if they have the same X
         * and Y coordinates. Should we check Z and M here too?
         */
        if (startX != endX || startY != endY) {
            throw new IllegalArgumentException();
        }
        return result;
    }

    private static void addRing(ArrayList<double[]> ring, Target target, boolean useZ, boolean useM) {
        for (int i = 0, size = ring.size(); i < size; i++) {
            double[] coordinates = ring.get(i);
            target.addCoordinate(coordinates[X], coordinates[Y], coordinates[Z], coordinates[M], i, size);
        }
    }

    private static void addCoordinate(EWKTSource source, Target target, boolean useZ, boolean useM, int index,
            int total) {
        double x = source.readCoordinate();
        double y = source.readCoordinate();
        double z = Double.NaN, m = Double.NaN;
        if (source.hasCoordinate()) {
            if (!useZ && useM) {
                m = source.readCoordinate();
            } else {
                z = source.readCoordinate();
                if (source.hasCoordinate()) {
                    m = source.readCoordinate();
                }
            }
        }
        target.addCoordinate(x, y, z, m, index, total);
    }

    private static double[] readCoordinate(EWKTSource source, boolean useZ, boolean useM) {
        double x = source.readCoordinate();
        double y = source.readCoordinate();
        double z = Double.NaN, m = Double.NaN;
        if (source.hasCoordinate()) {
            if (!useZ && useM) {
                m = source.readCoordinate();
            } else {
                z = source.readCoordinate();
                if (source.hasCoordinate()) {
                    m = source.readCoordinate();
                }
            }
        }
        return new double[] { x, y, z, m };
    }

    private EWKTUtils() {
    }

}
