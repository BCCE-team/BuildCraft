package buildcraft.lib.client.render.fluid;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.logging.log4j.Logger;

import com.google.common.collect.HashBiMap;
import com.mojang.blaze3d.platform.NativeImage;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.BCLib;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public class FrozenTextureGenPackResources implements PackResources{
	
	private static final Logger logger = BCLog.logger;
	private static final int SIZE = 2;//2

	private final HashBiMap<ResourceLocation, ResourceLocation> transLocations = HashBiMap.create();//<transformed, src>
	
	protected InputStream load(ResourceLocation srcLocation) {
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
		Optional<Resource> resourceOp = manager.getResource(srcLocation);
		if(resourceOp.isEmpty()) {
			logger.error("Can not read resource : "+srcLocation+" when creating forzen sprite, maybe called too early");
			return null;
		}
		Resource resource = resourceOp.get();

		try {
			NativeImage srcimage = NativeImage.read(resource.open());
            int widthOld = srcimage.getWidth();
	        try {
	        	var animationmetadatasection = resource.metadata().getSection(AnimationMetadataSection.SERIALIZER);
	            if (animationmetadatasection.isEmpty() && widthOld != srcimage.getWidth()) {
	                BCLog.logger.warn(
	                    "[lib.fluid] Failed to create a frozen sprite of " + srcLocation.toString()
	                        + " as the source sprite wasnn't an animation and had a different width ("
	                        + srcimage.getHeight() + ") from height (" + widthOld + ")!"
	                );
	                return null;
	            }
	        } catch (Exception exception) {
	        	logger.error("Unable to parse metadata from {} : {}", srcLocation, exception);
	        }

            int width = widthOld * SIZE;
            int height = width;

            NativeImage outImage = new NativeImage(width, height, false);
			for (int x = 0; x < width; x++) {
				int fx = (x % widthOld);
				for (int y = 0; y < height; y++) {
					int fy = y % widthOld;//heightOld;
					outImage.setPixelRGBA(x, y, srcimage.getPixelRGBA(fx, fy));
				}
			}
			ByteArrayInputStream data = new ByteArrayInputStream(outImage.asByteArray());
			if(outImage!=null)
				outImage.close();
			if(srcimage!=null)
				srcimage.close();
            return data;
		} catch (IOException e) {
			logger.error("fail to open NativeImage at "+srcLocation);
			buildcraft.lib.internal.debug.BCLog.logger.warn("Failed to read generated fluid texture", e);
		}
		return null;
	}
	
	
	public ResourceLocation registry(ResourceLocation srcLocation) {
		String path = "fluid_" + srcLocation.toString().replace(':', '_') + "_convert_frozen";
		transLocations.put(new ResourceLocation(BCLib.MODID, "textures/"+path+".png"),
				new ResourceLocation(srcLocation.getNamespace(),"textures/"+srcLocation.getPath()+".png"));
		return new ResourceLocation(BCLib.MODID, path);
	}
	
	@Override
	public InputStream getRootResource(String p_10294_) throws IOException {
		return null;
	}

	@Override
	public InputStream getResource(PackType type, ResourceLocation location) throws IOException {
		// Generated PNGs exist only in the client asset pack. Never offer their
		// binary contents to a data/recipe/guide loader looking for another file.
		if (type != PackType.CLIENT_RESOURCES) {
			return null;
		}
		ResourceLocation source = transLocations.get(location);
		return source == null ? null : load(source);
	}

	@Override
	public Collection<ResourceLocation> getResources(PackType type, String namespace, String pathPrefix,
			Predicate<ResourceLocation> filter) {
		// ResourceManager.listResources() relies on all four arguments. Returning
		// every frozen texture here made JSON listeners and AE2's guide loader
		// interpret PNG bytes as JSON/Markdown (1.19.2 IT16 crash).
		if (type != PackType.CLIENT_RESOURCES || !BCLib.MODID.equals(namespace)) {
			return Set.of();
		}
		return transLocations.keySet().stream()
			.filter(location -> location.getPath().startsWith(pathPrefix))
			.filter(filter)
			.toList();
	}

	@Override
	public boolean hasResource(PackType type, ResourceLocation location) {
		return type == PackType.CLIENT_RESOURCES && transLocations.containsKey(location);
	}

	@Override
	public Set<String> getNamespaces(PackType type) {
		return type == PackType.CLIENT_RESOURCES ? Set.of(BCLib.MODID) : Set.of();
	}

	@Override
	public <T> T getMetadataSection(MetadataSectionSerializer<T> p_10291_) throws IOException {
		return null;
	}

	@Override
	public String getName() {
		return "Generated";
	}

    public void clear() {
        transLocations.clear();
    }

	@Override
	public void close() {
        // This singleton pack is shared with the active resource reload. A resource manager may close its packs
        // while another reload is preparing the atlas, so closing must not clear the live generated mapping.
	}

}
