/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.render.laser;

import buildcraft.lib.client.model.MutableQuad;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public class LaserContext {
	public final Matrix3f matrix = new Matrix3f();
	private final Vector3f offset;
	private final Vector3f point = new Vector3f();
	private final Vector3f normal = new Vector3f();
	private final ILaserRenderer renderer;
	public final double length;
	private final boolean useNormalColour, drawBothSides, isStatic;
	private final int minBlockLight;

	public LaserContext(ILaserRenderer renderer, LaserData_BC8 data, boolean useNormalColour, boolean isCullEnabled, boolean isStatic) {
		this.renderer = renderer;
		this.useNormalColour = useNormalColour;
		this.drawBothSides = isCullEnabled;
		this.minBlockLight = data.minBlockLight;
		this.isStatic = isStatic;
		
		Vec3 delta = data.start.subtract(data.end);
		double dx = delta.x;
		double dy = delta.y;
		double dz = delta.z;

		final double angleY, angleZ;

		double realLength = delta.length();
		length = realLength / data.scale;
		angleZ = Math.PI - Math.atan2(dz, dx);
		double rl_squared = realLength * realLength;
		double dy_dy = dy * dy;
		if (dx == 0 && dz == 0) {
			final double angle = Math.PI / 2;
			if (dy < 0) {
				angleY = angle;
			} else {
				angleY = -angle;
			}
		} else {
			dx = Math.sqrt(rl_squared - dy_dy);
			angleY = -Math.atan2(dy, dx);
		}

		// Matrix steps:
		// 1: rotate angles (Y) to make everything work
		// 2: rotate angles (Z) to make everything work
		// 3: scale it by the laser's scale
		// 4: translate forward by "start"
		matrix.identity();
		// // Step 4
		offset = new Vector3f((float) data.start.x, (float) data.start.y, (float) data.start.z);
		// Step 3
		matrix.scale((float) data.scale, (float) data.scale, (float) data.scale);
		Quaternionf q = new Quaternionf(0.0F, (float) Math.sin((angleZ / 2.0F)), 0.0F, (float) Math.cos((angleZ / 2.0F)));
		q.mul(new Quaternionf(0.0F, 0.0F, (float) Math.sin((angleY / 2.0F)), (float) Math.cos((angleY / 2.0F))));
		matrix.rotate(q);
	}

	@OnlyIn(Dist.CLIENT)
	public void setFaceNormal(double nx, double ny, double nz) {
		if (useNormalColour) {
			normal.set((float) nx, (float) ny, (float) nz);
			matrix.transform(normal);
//			normal.add(offset);
			n[0] = normal.x();
			n[1] = normal.y();
			n[2] = normal.z();
			diffuse = MutableQuad.diffuseLight(n[0], n[1], n[2]);
		}
	}

	private int index = 0;
	private final double[] x = { 0, 0, 0, 0 };
	private final double[] y = { 0, 0, 0, 0 };
	private final double[] z = { 0, 0, 0, 0 };
	private final double[] u = { 0, 0, 0, 0 };
	private final double[] v = { 0, 0, 0, 0 };
	private final int[] l = { 0, 0, 0, 0 };
	private final float[] n = { 0, 1, 0 };
	private float diffuse;

	@OnlyIn(Dist.CLIENT)
	public void addPoint(double xIn, double yIn, double zIn, double uIn, double vIn) {
		point.set((float) xIn, (float) yIn, (float) zIn);
		matrix.transform(point);
		
		float rx = offset.x() + point.x();//real X position
		float ry = offset.y() + point.y();
		float rz = offset.z() + point.z();
		
		int lmap = LaserRenderer_BC8.computeLightmap(rx, ry, rz, minBlockLight);
		x[index] = isStatic ? rx : point.x();
		y[index] = isStatic ? ry : point.y();
		z[index] = isStatic ? rz : point.z();
		u[index] = uIn;
		v[index] = vIn;
		l[index] = lmap;
		index++;
		if (index == 4) {
			index = 0;
			vertex(0);
			vertex(1);
			vertex(2);
			vertex(3);
			if (drawBothSides) {
				n[0] = -n[0];
				n[1] = -n[1];
				n[2] = -n[2];
				diffuse = MutableQuad.diffuseLight(n[0], n[1], n[2]);
				vertex(3);
				vertex(2);
				vertex(1);
				vertex(0);
			}
			n[0] = 0;
			n[1] = 1;
			n[2] = 0;
		}
	}

	private void vertex(int i) {
		if (useNormalColour) {
			renderer.vertex((float)x[i], (float)y[i], (float)z[i], (float) u[i], (float) v[i], l[i], n[0], n[1], n[2], diffuse);
		} else {
			renderer.vertex((float)x[i], (float)y[i], (float)z[i], (float) u[i], (float) v[i], l[i], 0, 1, 0, 1);
		}
	}
}
