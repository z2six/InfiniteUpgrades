// MainFile: src/main/java/org/z2six/infiniteupgrades/core/bootstrap/client/StatueModelPatcher.java
package org.z2six.infiniteupgrades.core.bootstrap.client;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * Patches Blockbench JSON for the Angel/Demon statue block models at bake time.
 * - Rounds illegal element rotation angles to nearest of {-45, -22.5, 0, 22.5, 45}
 * - Fixes 0-size UVs (nudge by a tiny epsilon)
 * - Re-bakes *each* blockstate variant (facing=N/E/S/W) with the proper Y rotation
 *
 * Defensive: on any error, logs and leaves the original baked model as-is.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID, value = Dist.CLIENT)
public final class StatueModelPatcher {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    // Base model ids (no variant)
    private static final ResourceLocation ANGEL_BASE =
            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "block/angel_statue");
    private static final ResourceLocation DEMON_BASE =
            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "block/demon_statue");

    // Legal angles for vanilla block models
    private static final double[] LEGAL_ANGLES = new double[]{-45.0, -22.5, 0.0, 22.5, 45.0};

    private StatueModelPatcher() {}

    @net.neoforged.bus.api.SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional e) {
        // Not strictly required for blockstate models, but harmless; keep for completeness.
        try {
            e.register(ModelResourceLocation.standalone(ANGEL_BASE));
            e.register(ModelResourceLocation.standalone(DEMON_BASE));
            LOG.debug("[StatueModelPatcher] RegisterAdditional OK (angel/demon)");
        } catch (Throwable t) {
            LOG.error("[StatueModelPatcher] RegisterAdditional failed: {}", t.toString());
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onModifyBaking(ModelEvent.ModifyBakingResult e) {
        try {
            final ModelBakery bakery = e.getModelBakery();
            final Function<Material, TextureAtlasSprite> atlasGetter = atlasGetter();

            // We need to replace *variants* such as "angel_statue#facing=east"
            // So iterate all baked models and grab the ones whose base id is ours.
            Map<ModelResourceLocation, BakedModel> baked = e.getModels();
            if (baked.isEmpty()) return;

            // Load and sanitize JSON once per base id
            JsonObject angelJson = loadAndSanitizeJson(ANGEL_BASE);
            JsonObject demonJson = loadAndSanitizeJson(DEMON_BASE);

            // Prebuild BlockModel from sanitized JSONs
            BlockModel angelModel = (angelJson != null ? BlockModel.fromString(angelJson.toString()) : null);
            BlockModel demonModel = (demonJson != null ? BlockModel.fromString(demonJson.toString()) : null);

            // Track entries to replace (avoid concurrent modification)
            List<Map.Entry<ModelResourceLocation, BakedModel>> replacements = new ArrayList<>();

            for (var entry : baked.entrySet()) {
                ModelResourceLocation mrl = entry.getKey();
                ResourceLocation base = mrl.id();

                boolean isAngel = base.equals(ANGEL_BASE);
                boolean isDemon = base.equals(DEMON_BASE);
                if (!isAngel && !isDemon) continue;

                BlockModel source = isAngel ? angelModel : demonModel;
                if (source == null) {
                    // If model couldn't be loaded/sanitized, keep the original baked (probably missing-model)
                    LOG.warn("[StatueModelPatcher] No sanitized model for {}", base);
                    continue;
                }

                try {
                    // Derive the Y rotation from the variant string (e.g. "facing=east")
                    BlockModelRotation rot = rotationForVariant(mrl.variant());
                    BakedModel bakedFixed = source.bake((ModelBaker) bakery, source, atlasGetter, rot, false);
                    if (bakedFixed != null) {
                        replacements.add(Map.entry(mrl, bakedFixed));
                        LOG.debug("[StatueModelPatcher] Re-baked {} with rot {}", mrl, rot);
                    } else {
                        LOG.warn("[StatueModelPatcher] Bake returned null for {}", mrl);
                    }
                } catch (Throwable t) {
                    LOG.error("[StatueModelPatcher] Failed to bake {}: {}", mrl, t.toString());
                }
            }

            // Apply replacements
            for (var rep : replacements) {
                baked.put(rep.getKey(), rep.getValue());
            }
        } catch (Throwable t) {
            LOG.error("[StatueModelPatcher] ModifyBakingResult failed: {}", t.toString());
        }
    }

    /** Use the live BLOCK_ATLAS for sprite lookup. */
    private static Function<Material, TextureAtlasSprite> atlasGetter() {
        try {
            TextureAtlas live = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
            return (Material mat) -> live.getSprite(mat.texture());
        } catch (Throwable t) {
            LOG.error("[StatueModelPatcher] Failed to acquire atlas getter: {}", t.toString());
            // Fallback: return a dummy function that will probably yield missing-texture
            return (mat) -> Minecraft.getInstance().getModelManager().getMissingModel().getParticleIcon();
        }
    }

    /** Load JSON model and sanitize rotations & zero-size UVs. */
    private static JsonObject loadAndSanitizeJson(ResourceLocation baseId) {
        try {
            ResourceLocation resId = ResourceLocation.fromNamespaceAndPath(
                    baseId.getNamespace(), "models/" + baseId.getPath() + ".json");

            var opt = Minecraft.getInstance().getResourceManager().getResource(resId);
            if (opt.isEmpty()) {
                LOG.warn("[StatueModelPatcher] Model JSON not found: {}", resId);
                return null;
            }

            try (var is = opt.get().open();
                 var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                if (root == null) return null;

                // Ensure a vanilla parent so transforms are well-defined (harmless if already set)
                root.addProperty("parent", root.has("parent") ? root.get("parent").getAsString() : "block/block");

                // Sanitize "elements"
                var elements = root.getAsJsonArray("elements");
                if (elements != null) {
                    for (var el : elements) {
                        if (!el.isJsonObject()) continue;
                        var elem = el.getAsJsonObject();
                        sanitizeRotation(elem);
                        sanitizeFaces(elem);
                    }
                }
                return root;
            }
        } catch (Throwable t) {
            LOG.error("[StatueModelPatcher] loadAndSanitizeJson failed for {}: {}", baseId, t.toString());
            return null;
        }
    }

    private static void sanitizeRotation(JsonObject elem) {
        try {
            if (!elem.has("rotation")) return;
            var rot = elem.getAsJsonObject("rotation");
            if (!rot.has("angle")) return;
            double angle = rot.get("angle").getAsDouble();
            double rounded = nearestLegal(angle);
            if (Math.abs(rounded - angle) > 1e-6) rot.addProperty("angle", rounded);
        } catch (Throwable ignored) {}
    }

    private static double nearestLegal(double angle) {
        double best = LEGAL_ANGLES[0];
        double bestD = Math.abs(angle - best);
        for (double a : LEGAL_ANGLES) {
            double d = Math.abs(angle - a);
            if (d < bestD) { bestD = d; best = a; }
        }
        return best;
    }

    private static void sanitizeFaces(JsonObject elem) {
        try {
            if (!elem.has("faces")) return;
            var faces = elem.getAsJsonObject("faces");
            for (var en : faces.entrySet()) {
                var face = en.getValue().getAsJsonObject();
                if (!face.has("uv")) continue;
                var uv = face.getAsJsonArray("uv");
                if (uv.size() != 4) continue;

                double u0 = uv.get(0).getAsDouble();
                double v0 = uv.get(1).getAsDouble();
                double u1 = uv.get(2).getAsDouble();
                double v1 = uv.get(3).getAsDouble();

                boolean changed = false;
                double eps = 0.0005;
                if (Math.abs(u0 - u1) < 1e-9) { u1 += eps; changed = true; }
                if (Math.abs(v0 - v1) < 1e-9) { v1 += eps; changed = true; }

                if (changed) {
                    JsonArray fixed = new JsonArray();
                    fixed.add(u0); fixed.add(v0); fixed.add(u1); fixed.add(v1);
                    face.add("uv", fixed);
                }
            }
        } catch (Throwable t) {
            LOG.error("[StatueModelPatcher] sanitizeFaces failed: {}", t.toString());
        }
    }

    /** Map "facing=..." variant to the correct BlockModelRotation (only Y needed). */
    private static BlockModelRotation rotationForVariant(String variant) {
        // variant looks like: "facing=north" / "facing=east" / etc.
        try {
            String f = null;
            for (String part : variant.split(",")) {
                part = part.trim();
                if (part.startsWith("facing=")) {
                    f = part.substring("facing=".length());
                    break;
                }
            }
            if (f == null) return BlockModelRotation.X0_Y0;

            return switch (f) {
                case "south" -> BlockModelRotation.X0_Y180;
                case "west"  -> BlockModelRotation.X0_Y270;
                case "east"  -> BlockModelRotation.X0_Y90;
                default      -> BlockModelRotation.X0_Y0; // north
            };
        } catch (Throwable t) {
            LOG.error("[StatueModelPatcher] rotationForVariant parse failed for '{}': {}", variant, t.toString());
            return BlockModelRotation.X0_Y0;
        }
    }
}
