package plugins.adufour.connectedcomponents;

import icy.image.IcyBufferedImage;
import icy.plugin.abstract_.Plugin;
import icy.plugin.interface_.PluginBundled;
import icy.sequence.Sequence;
import icy.type.DataType;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.vecmath.Point2d;
import javax.vecmath.Point3d;
import javax.vecmath.Point3i;
import javax.vecmath.SingularMatrixException;
import javax.vecmath.Vector3d;

import plugins.adufour.blocks.lang.Block;
import plugins.adufour.blocks.util.VarList;
import plugins.adufour.quickhull.QuickHull2D;
import plugins.adufour.quickhull.QuickHull3D;
import plugins.adufour.vars.lang.Var;
import plugins.adufour.vars.lang.VarDouble;
import Jama.EigenvalueDecomposition;
import Jama.Matrix;

public class ConnectedComponentDescriptor extends Plugin implements PluginBundled, Block
{
    Var<ConnectedComponent> varCC = new Var<ConnectedComponent>("Connected component", ConnectedComponent.class);
    
    VarDouble perimeter = new VarDouble("perimeter", 0.0);
    
    VarDouble sphericity = new VarDouble("sphericity", 0.0);
    
    VarDouble eccentricity = new VarDouble("eccentricity", 0.0);
    
    VarDouble longAxis = new VarDouble("long diameter", 0.0);
    
    VarDouble shortAxis = new VarDouble("short diameter", 0.0);
    
    VarDouble shortAxisZ = new VarDouble("short diameter (Z)", 0.0);
    
    @Override
    public String getMainPluginClassName()
    {
        return ConnectedComponents.class.getName();
    }
    
    @Override
    public void declareInput(VarList inputMap)
    {
        inputMap.add("component", varCC);
    }
    
    @Override
    public void declareOutput(VarList outputMap)
    {
        outputMap.add("perimeter", perimeter);
        outputMap.add("long diameter", longAxis);
        outputMap.add("short diameter", shortAxis);
        outputMap.add("short diameter (Z)", shortAxisZ);
        outputMap.add("eccentricity", eccentricity);
        outputMap.add("sphericity", sphericity);
    }
    
    @Override
    public void run()
    {
        ConnectedComponent cc = varCC.getValue();
        
        if (cc == null) return;
        
        perimeter.setValue(computePerimeter(cc, null, null));
        
        double[] ellipseDimensions = computeEllipseDimensions(cc);
        
        longAxis.setValue(ellipseDimensions[0] * 2);
        shortAxis.setValue(ellipseDimensions[1] * 2);
        eccentricity.setValue(ellipseDimensions[0] == 0.0 ? 0.0 : ellipseDimensions[1] / ellipseDimensions[0]);
        
        sphericity.setValue(computeSphericity(cc));
    }
    
    /**
     * @param cc
     * @return a triplet representing the radiuses of the best fitting ellipse (the third value is 0
     *         for 2D objects)
     */
    public double[] computeEllipseDimensions(ConnectedComponent cc)
    {
        double[] axes = new double[3];
        
        try
        {
            if (is2D(cc))
            {
                Point2d radii = new Point2d();
                computeEllipse(cc, null, radii, null, null);
                if (radii.x > radii.y)
                {
                    axes[0] = radii.x;
                    axes[1] = radii.y;
                }
                else
                {
                    axes[0] = radii.y;
                    axes[1] = radii.x;
                }
            }
            else
            {
                Point3d radii = new Point3d();
                computeEllipse(cc, null, radii, null, null);
                axes[0] = radii.x;
                axes[1] = radii.y;
                axes[2] = radii.z;
            }
        }
        catch (Exception e)
        {
        }
        
        return axes;
    }
    
    /**
     * Computes the bounding box of this component, and stores the result into the given arguments
     * 
     * @param cc
     *            the input component
     * @param minBounds
     *            the first corner of the bounding box in X-Y-Z order (Upper-Left hand-Top)
     * @param maxBounds
     *            the second corner of the bounding box in X-Y-Z order (Lower-Right hand-Bottom)
     */
    public void computeBoundingBox(ConnectedComponent cc, Point3i minBounds, Point3i maxBounds)
    {
        if (minBounds != null)
        {
            minBounds.x = Integer.MAX_VALUE;
            minBounds.y = Integer.MAX_VALUE;
            minBounds.z = Integer.MAX_VALUE;
        }
        
        if (maxBounds != null)
        {
            maxBounds.x = 0;
            maxBounds.y = 0;
            maxBounds.z = 0;
        }
        
        for (Point3i point : cc)
        {
            if (minBounds != null)
            {
                minBounds.x = Math.min(minBounds.x, point.x);
                minBounds.y = Math.min(minBounds.y, point.y);
                minBounds.z = Math.min(minBounds.z, point.z);
            }
            if (maxBounds != null)
            {
                maxBounds.x = Math.max(maxBounds.x, point.x);
                maxBounds.y = Math.max(maxBounds.y, point.y);
                maxBounds.z = Math.max(maxBounds.z, point.z);
            }
        }
    }
    
    /**
     * Computes the bounding box of this component, and stores the result into the given arguments
     * 
     * @param cc
     *            the input component
     * @param bsCenter
     *            the computed center of the bounding sphere
     * @param maxBounds
     *            the computed radius of the bounding sphere
     */
    public void computeBoundingSphere(ConnectedComponent cc, Point3d bsCenter, VarDouble bsRadius)
    {
        bsCenter.set(cc.getMassCenter());
        bsRadius.setValue(cc.getMaxDistanceTo(bsCenter));
    }
    
    /**
     * @param contourPoints
     *            (set to <code>null</code> if not wanted) an output list of extracted contour
     *            points
     * @param outputSequence
     *            (set to <code>null</code> if not wanted) an output sequence to receive the
     *            extracted contour
     * @return The 3D perimeter (or 3D surface) of this component
     */
    public double computePerimeter(ConnectedComponent cc, ArrayList<Point3i> contourPoints, Sequence outputSequence)
    {
        double perimeter = 0;
        
        if (contourPoints != null) contourPoints.ensureCapacity(cc.getSize() / 2);
        
        Point3i min = new Point3i(), max = new Point3i();
        computeBoundingBox(cc, min, max);
        
        Sequence mask = cc.toSequence();
        int w = mask.getSizeX();
        int h = mask.getSizeY();
        int d = mask.getSizeZ();
        
        byte[][] outputMask = null;
        
        if (outputSequence != null)
        {
            outputSequence.removeAllImages();
            for (int i = 0; i < d; i++)
            {
                outputSequence.setImage(0, i, new IcyBufferedImage(w, h, 1, DataType.UBYTE));
            }
            
            outputMask = outputSequence.getDataXYZAsByte(0, 0);
        }
        
        byte[][] mask_z_xy = mask.getDataXYZAsByte(0, 0);
        
        Point3i localP = new Point3i();
        
        // count the edges and corners in 2D/3D
        double a = 0, b = 0;
        
        for (Point3i p : cc)
        {
            localP.sub(p, min);
            
            int xy = localP.y * w + localP.x;
            
            byte[] z = mask_z_xy[localP.z];
            
            int nbEdges = 0;
            
            if (localP.x == 0 || z[xy - 1] == 0) nbEdges++;
            if (localP.x == w - 1 || z[xy + 1] == 0) nbEdges++;
            if (localP.y == 0 || z[xy - w] == 0) nbEdges++;
            if (localP.y == h - 1 || z[xy + w] == 0) nbEdges++;
            if (min.z != max.z)
            { // 3D
                if (localP.z == 0 || mask_z_xy[localP.z - 1][xy] == 0) nbEdges++;
                if (localP.z == d - 1 || mask_z_xy[localP.z + 1][xy] == 0) nbEdges++;
            }
            
            switch (nbEdges)
            {
            case 0:
                break;
            case 1:
                a++;
                perimeter++;
                break;
            case 2:
                b++;
                perimeter += Math.sqrt(2);
                break;
            case 3:
                b += 2;
                perimeter += 2 * Math.sqrt(2);
                break;
            default:
                perimeter += Math.sqrt(3);
            }
            
            if (nbEdges > 0)
            {
                if (contourPoints != null) contourPoints.add(p);
                if (outputMask != null) outputMask[localP.z][xy] = (byte) 1;
            }
        }
        
        // adjust the perimeter empirically according to the edge/corner distribution
        perimeter += Math.round(perimeter / cc.getSize()) - Math.min(a / 10, b);
        
        return perimeter;
    }
    
    /**
     * Compute the sphericity (circularity in 2D) of the given component, measured as a weighted
     * ratio between the component's dimensions.<br/>
     * NOTE: the circularity index is adjusted to work with digitized contours, and partially
     * corrects for digitization artifacts (see the
     * {@link #computePerimeter(ConnectedComponent, ArrayList, Sequence)} method)
     * 
     * @param cc
     *            the input component
     * @return 1 for a perfect circle (or sphere), and lower than 1 otherwise
     */
    public double computeSphericity(ConnectedComponent cc)
    {
        double dim = is2D(cc) ? 2.0 : 3.0;
        
        double area = cc.getSize();
        double peri = computePerimeter(cc, null, null);
        
        // some verification code
        //
        // Point3i minBB = new Point3i(), maxBB = new Point3i();
        // computeBoundingBox(cc, minBB, maxBB);
        //
        // double radius = (maxBB.x - minBB.x + 1) / 2;
        // // 2D = 2.PI.R, 3D = 4.PI.R^2 <=> 2^(D-1).PI.R^(D-1)
        // double p_real = Math.pow(2, dim - 1) * Math.PI * Math.pow(radius, dim - 1);
        // System.out.println("p = " + peri + ", should be " + p_real + " => " + (peri / p_real));
        // // 2D = PI.R^2, 3D = 4/3.PI.R^3 <=> (D+1)/3.PI.R^D
        // double s_real = (dim + 1) * Math.PI * Math.pow(radius, dim) / 3;
        // System.out.println("s = " + area + ", should be " + s_real + " => " + (area / s_real));
        //
        // end of the verification code
        
        double sph = (Math.pow(Math.PI, 1.0 / dim) / peri) * Math.pow(area * dim * 2, (dim - 1) / dim);
        
        // adjust final rounding off errors (sphericity is always below 1)
        return Math.min(1.0, sph);
    }
    
    /**
     * Computes the eccentricity of the given component. This method fits an ellipse with radii a
     * and b (in 2D) or an ellipsoid with radii a, b and c (in 3D) and returns in both cases the
     * ratio b/a
     * 
     * @param cc
     *            the input component
     * @return the ratio b/a, where a and b are the two first largest ellipse radii (there are only
     *         two in 2D)
     */
    public double computeEccentricity(ConnectedComponent cc)
    {
        if (is2D(cc))
        {
            try
            {
                Point2d radii = new Point2d();
                computeEllipse(cc, null, radii, null, null);
                return radii.x / radii.y;
            }
            catch (RuntimeException e)
            {
                // error during the ellipse computation
                return Double.NaN;
            }
        }
        else
        {
            Point3d radii = new Point3d();
            try
            {
                computeEllipse(cc, null, radii, null, null);
                return radii.x / radii.y;
            }
            catch (Exception e)
            {
                return Double.NaN;
            }
        }
    }
    
    /**
     * @param cc
     * @return The hull ratio, measured as the ratio between the object volume and its convex hull
     *         (envelope)
     */
    public double computeHullRatio(ConnectedComponent cc)
    {
        double hull = computeConvexAreaAndVolume(cc)[1];
        
        return hull == 0.0 ? 0.0 : Math.min(1.0, cc.getSize() / hull);
    }
    
    /**
     * @param cc
     * @return An array containing [contour, area] of the smallest convex envelope surrounding the
     *         object. The 2 values are returned together because their computation is simultaneous
     *         (in the 3D case only)
     */
    public double[] computeConvexAreaAndVolume(ConnectedComponent cc)
    {
        int i = 0, n = cc.getSize();
        if (n == 1) return new double[] { 0.0, 1.0 };
        
        double contour = 0.0;
        double area = 0.0;
        
        if (is2D(cc))
        {
            List<Point2D> points = new ArrayList<Point2D>();
            
            for (Point3i p : cc)
                points.add(new Point2D.Double(p.x, p.y));
                
            if (points.size() > 4) points = QuickHull2D.computeConvexEnvelope(points);
            
            // volume = sum( sqrt[ (x[i] - x[i-1])^2 + (y[i] - y[i-1])^2 ] )
            // area = 0.5 * sum( (x[i-1] * y[i]) - (y[i-1] * x[i]) )
            
            Point2D p1 = points.get(points.size() - 1), p2 = null;
            
            for (i = 0; i < points.size(); i++)
            {
                p2 = points.get(i);
                contour += p1.distance(p2);
                area += (p2.getX() * p1.getY()) - (p2.getY() * p1.getX());
                p1 = p2;
            }
            
            area *= 0.5;
        }
        else try
        {
            Point3d[] points = new Point3d[n];
            
            for (Point3i p : cc)
                points[i++] = new Point3d(p.x, p.y, p.z);
                
            QuickHull3D qhull = new QuickHull3D(points);
            int[][] hullFaces = qhull.getFaces();
            Point3d[] hullPoints = qhull.getVertices();
            
            Vector3d v12 = new Vector3d();
            Vector3d v13 = new Vector3d();
            Vector3d cross = new Vector3d();
            
            for (int[] face : hullFaces)
            {
                Point3d p1 = hullPoints[face[0]];
                Point3d p2 = hullPoints[face[1]];
                Point3d p3 = hullPoints[face[2]];
                
                v12.sub(p2, p1);
                v13.sub(p3, p1);
                cross.cross(v12, v13);
                
                contour = cross.length() * 0.5;
                
                cross.normalize();
                area += contour * cross.x * (p1.x + p2.x + p3.x);
            }
        }
        catch (IllegalArgumentException e)
        {
            // less than 4 points, or coplanarity detected
            return new double[] { n, n };
        }
        
        return new double[] { contour, area };
    }
    
    /**
     * Computes the geometric moment of the given component
     * 
     * @param cc
     *            the input component
     * @param p
     *            the moment order along X
     * @param q
     *            the moment order along Y
     * @param r
     *            the moment order along Z (set to 0 if the object is 2D)
     * @return the geometric moment
     */
    public double computeGeometricMoment(ConnectedComponent cc, int p, int q, int r)
    {
        double moment = 0;
        
        Point3d center = cc.getMassCenter();
        
        if (is2D(cc))
        {
            for (Point3i point : cc)
                moment += Math.pow(point.x - center.x, p) * Math.pow(point.y - center.y, q);
        }
        else
        {
            for (Point3i point : cc)
                moment += Math.pow(point.x - center.x, p) * Math.pow(point.y - center.y, q) * Math.pow(point.z - center.z, r);
        }
        return moment;
    }
    
    /**
     * Compute the best fitting ellipsoid for the given component.<br>
     * This method is adapted from Yury Petrov's Matlab code and ported to Java by the BoneJ project
     * 
     * @param cc
     *            the component to fit
     * @param center
     *            (set to null if not wanted) the calculated ellipsoid center
     * @param radii
     *            (set to null if not wanted) the calculated ellipsoid radius in each
     *            eigen-direction
     * @param eigenVectors
     *            (set to null if not wanted) the calculated ellipsoid eigen-vectors
     * @param equation
     *            (set to null if not wanted) an array of size 9 containing the calculated ellipsoid
     *            equation
     * @throws IllegalArgumentException
     *             if the number of points in the component is too low (minimum is 9)
     * @throws SingularMatrixException
     *             if the component is flat (i.e. lies in a 2D plane)
     */
    public void computeEllipse(ConnectedComponent cc, Point3d center, Point3d radii, Vector3d[] eigenVectors, double[] equation) throws IllegalArgumentException
    {
        int nPoints = cc.getSize();
        if (nPoints < 9)
        {
            throw new IllegalArgumentException("Too few points; need at least 9 to calculate a unique ellipsoid");
        }
        
        Point3i[] points = cc.getPoints();
        
        double[][] d = new double[nPoints][9];
        for (int i = 0; i < nPoints; i++)
        {
            double x = points[i].x;
            double y = points[i].y;
            double z = points[i].z;
            d[i][0] = (x * x);
            d[i][1] = (y * y);
            d[i][2] = (z * z);
            d[i][3] = (2.0D * x * y);
            d[i][4] = (2.0D * x * z);
            d[i][5] = (2.0D * y * z);
            d[i][6] = (2.0D * x);
            d[i][7] = (2.0D * y);
            d[i][8] = (2.0D * z);
        }
        
        Matrix D = new Matrix(d);
        Matrix ones = ones(nPoints, 1);
        
        Matrix V;
        try
        {
            V = D.transpose().times(D).inverse().times(D.transpose().times(ones));
        }
        catch (RuntimeException e)
        {
            throw new SingularMatrixException("The component is most probably flat (i.e. lies in a 2D plane)");
        }
        
        double[] v = V.getColumnPackedCopy();
        
        double[][] a = { { v[0], v[3], v[4], v[6] }, { v[3], v[1], v[5], v[7] }, { v[4], v[5], v[2], v[8] }, { v[6], v[7], v[8], -1.0D } };
        Matrix A = new Matrix(a);
        Matrix C = A.getMatrix(0, 2, 0, 2).times(-1.0D).inverse().times(V.getMatrix(6, 8, 0, 0));
        Matrix T = Matrix.identity(4, 4);
        T.setMatrix(3, 3, 0, 2, C.transpose());
        Matrix R = T.times(A.times(T.transpose()));
        double r33 = R.get(3, 3);
        Matrix R02 = R.getMatrix(0, 2, 0, 2);
        EigenvalueDecomposition E = new EigenvalueDecomposition(R02.times(-1.0D / r33));
        Matrix eVal = E.getD();
        Matrix eVec = E.getV();
        Matrix diagonal = diag(eVal);
        
        if (radii != null) radii.set(Math.sqrt(1.0D / diagonal.get(0, 0)), Math.sqrt(1.0D / diagonal.get(1, 0)), Math.sqrt(1.0D / diagonal.get(2, 0)));
        
        if (center != null) center.set(C.get(0, 0), C.get(1, 0), C.get(2, 0));
        
        if (eigenVectors != null && eigenVectors.length == 3)
        {
            eigenVectors[0] = new Vector3d(eVec.get(0, 0), eVec.get(0, 1), eVec.get(0, 2));
            eigenVectors[1] = new Vector3d(eVec.get(1, 0), eVec.get(1, 1), eVec.get(1, 2));
            eigenVectors[2] = new Vector3d(eVec.get(2, 0), eVec.get(2, 1), eVec.get(2, 2));
        }
        if (equation != null && equation.length == 9) System.arraycopy(v, 0, equation, 0, v.length);
    }
    
    /**
     * 2D direct ellipse fitting.<br>
     * (Java port of Chernov's MATLAB implementation of the direct ellipse fit)
     * 
     * @param cc
     *            the component to fit
     * @param center
     *            (set to null if not wanted) the calculated ellipse center
     * @param radii
     *            (set to null if not wanted) the calculated ellipse radius in each eigen-direction
     * @param angle
     *            (set to null if not wanted) the calculated ellipse orientation
     * @param equation
     *            (set to null if not wanted) a 6-element array, {a b c d f g}, which are the
     *            calculated algebraic parameters of the fitting ellipse: <i>ax</i><sup>2</sup> + 2
     *            <i>bxy</i> + <i>cy</i><sup>2</sup> +2<i>dx</i> + 2<i>fy</i> + <i>g</i> = 0. The
     *            vector <b>A</b> represented in the array is normed, so that ||<b>A</b>||=1.
     * @throws RuntimeException
     *             if the ellipse calculation fails (e.g. if a singular matrix is detected)
     */
    public void computeEllipse(ConnectedComponent cc, Point2d center, Point2d radii, VarDouble angle, double[] equation) throws RuntimeException
    {
        Point3i[] points = cc.getPoints();
        Point3d ccenter = cc.getMassCenter();
        
        double[][] d1 = new double[cc.getSize()][3];
        double[][] d2 = new double[cc.getSize()][3];
        
        for (int i = 0; i < d1.length; i++)
        {
            final double xixC = points[i].x - ccenter.x;
            final double yiyC = points[i].y - ccenter.y;
            
            d1[i][0] = xixC * xixC;
            d1[i][1] = xixC * yiyC;
            d1[i][2] = yiyC * yiyC;
            
            d2[i][0] = xixC;
            d2[i][1] = yiyC;
            d2[i][2] = 1;
        }
        
        Matrix D1 = new Matrix(d1);
        Matrix D2 = new Matrix(d2);
        
        Matrix S1 = D1.transpose().times(D1);
        
        Matrix S2 = D1.transpose().times(D2);
        
        Matrix S3 = D2.transpose().times(D2);
        
        Matrix T = (S3.inverse().times(-1)).times(S2.transpose());
        
        Matrix M = S1.plus(S2.times(T));
        
        double[][] m = M.getArray();
        double[][] n = { { m[2][0] / 2, m[2][1] / 2, m[2][2] / 2 }, { -m[1][0], -m[1][1], -m[1][2] }, { m[0][0] / 2, m[0][1] / 2, m[0][2] / 2 } };
        
        Matrix N = new Matrix(n);
        
        EigenvalueDecomposition E = N.eig();
        Matrix eVec = E.getV();
        
        Matrix R1 = eVec.getMatrix(0, 0, 0, 2);
        Matrix R2 = eVec.getMatrix(1, 1, 0, 2);
        Matrix R3 = eVec.getMatrix(2, 2, 0, 2);
        
        Matrix cond = (R1.times(4)).arrayTimes(R3).minus(R2.arrayTimes(R2));
        
        int _f = 0;
        for (int i = 0; i < 3; i++)
        {
            if (cond.get(0, i) > 0)
            {
                _f = i;
                break;
            }
        }
        Matrix A1 = eVec.getMatrix(0, 2, _f, _f);
        
        Matrix A = new Matrix(6, 1);
        A.setMatrix(0, 2, 0, 0, A1);
        A.setMatrix(3, 5, 0, 0, T.times(A1));
        
        double[] ell = A.getColumnPackedCopy();
        double a4 = ell[3] - 2 * ell[0] * ccenter.x - ell[1] * ccenter.y;
        double a5 = ell[4] - 2 * ell[2] * ccenter.y - ell[1] * ccenter.x;
        double a6 = ell[5] + ell[0] * ccenter.x * ccenter.x + ell[2] * ccenter.y * ccenter.y + ell[1] * ccenter.x * ccenter.y - ell[3] * ccenter.x - ell[4] * ccenter.y;
        A.set(3, 0, a4);
        A.set(4, 0, a5);
        A.set(5, 0, a6);
        A = A.times(1 / A.normF());
        
        ell = A.getColumnPackedCopy();
        
        if (equation != null && equation.length != 6) System.arraycopy(ell, 0, equation, 0, 6);
        
        // Convert the general ellipse equation ax2 + bxy + cy2 +dx + fy + g = 0
        // into geometric parameters: center, radii and orientation.
        final double a = ell[0];
        final double b = ell[1] / 2;
        final double c = ell[2];
        final double d = ell[3] / 2;
        final double f = ell[4] / 2;
        final double g = ell[5];
        
        // centre
        final double cX = (c * d - b * f) / (b * b - a * c);
        final double cY = (a * f - b * d) / (b * b - a * c);
        
        // semiaxis length
        final double af = 2 * (a * f * f + c * d * d + g * b * b - 2 * b * d * f - a * c * g);
        
        final double aL = Math.sqrt((af) / ((b * b - a * c) * (Math.sqrt((a - c) * (a - c) + 4 * b * b) - (a + c))));
        
        final double bL = Math.sqrt((af) / ((b * b - a * c) * (-Math.sqrt((a - c) * (a - c) + 4 * b * b) - (a + c))));
        double phi = 0;
        if (b == 0)
        {
            if (a <= c) phi = 0;
            else if (a > c) phi = Math.PI / 2;
        }
        else
        {
            if (a < c) phi = Math.atan(2 * b / (a - c)) / 2;
            else if (a > c) phi = Math.atan(2 * b / (a - c)) / 2 + Math.PI / 2;
        }
        
        if (center != null) center.set(cX, cY);
        if (radii != null) radii.set(aL, bL);
        if (angle != null) angle.setValue(phi);
    }
    
    public boolean is2D(ConnectedComponent cc)
    {
        Point3i minBB = new Point3i();
        Point3i maxBB = new Point3i();
        computeBoundingBox(cc, minBB, maxBB);
        
        return minBB.z == maxBB.z;
    }
    
    private Matrix diag(Matrix matrix)
    {
        int min = Math.min(matrix.getRowDimension(), matrix.getColumnDimension());
        double[][] diag = new double[min][1];
        for (int i = 0; i < min; i++)
        {
            diag[i][0] = matrix.get(i, i);
        }
        return new Matrix(diag);
    }
    
    private Matrix ones(int m, int n)
    {
        double[][] array = new double[m][n];
        for (double[] row : array)
            Arrays.fill(row, 1.0);
        return new Matrix(array, m, n);
    }
}
