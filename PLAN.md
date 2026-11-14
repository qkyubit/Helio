# Helio — path-traced Minecraft for Linux (working name)

Fabric mod that adds a hardware path tracer and DLSS (SR / RR / MFG) on top of
Minecraft Java's own Vulkan backend, running natively on Linux.

## Target

| Thing | Choice | Why |
|---|---|---|
| Minecraft | **26.3** (latest release, 2026-09-15) | Vanilla Vulkan backend exists (experimental since 26.2). Sodium 0.9.2 / Iris 1.11.7 already on it. Port to 26.4 once it releases. |
| Loader | Fabric + Fabric API | Requested. |
| Java | 25 (what MC 26.x ships with) | Gives stable Panama FFM: call NGX's C API with no JNI/C++ build step. |
| DLSS SDK | **310.9.16** (latest, 2026-10-16) | Has Linux `.so` builds of SR (`libnvidia-ngx-dlss`), RR (`libnvidia-ngx-dlssd`), FG (`libnvidia-ngx-dlssg`). MFG modes added in 310.6.0. |
| Driver | 615.78.08 | Ships `libnvidia-ngx.so` and `VK_NV_low_latency2` rev 3 (Reflex + FG latency). |

### Dev machine (from fastfetch / nvidia-smi / vulkaninfo)

- Ryzen 5 7600X, 16 GB RAM, RTX 5060 Ti **16 GB** (Blackwell, sm_120)
- 2560x1440 @ 180 Hz, KDE Plasma 6.8 / KWin Wayland, CachyOS kernel 7.3
- Vulkan exts present: `KHR_acceleration_structure`, `KHR_ray_query`,
  `KHR_ray_tracing_pipeline`, `EXT_opacity_micromap`, `EXT_mesh_shader`,
  `NV_low_latency2`, `NV_optical_flow`, `KHR_present_wait`

16 GB RAM is the tighter budget: give the instance 6–8 GB heap, keep native
allocations (BLAS staging) bounded.

## Architecture

```
 Minecraft 26.3 (Vulkan backend)
   │  mixins: device creation, frame begin/end, level render, swapchain
   ▼
 Helio
   vk/      enable RT + NGX + low_latency2 extensions on Mojang's VkDevice;
            accel-structure builder, pipelines, GPU timers
   world/   chunk-section mesh → BLAS (async, budgeted), entities → dynamic BLAS, TLAS per frame
   render/  frame graph:
              raster G-buffer + motion vectors
              → RT direct light (sun/moon, ReSTIR DI for emissive blocks)
              → RT GI (1–2 bounces + spatial-hash radiance cache)
              → reflections / refraction (water, glass, metals)
              → DLSS RR (denoise + upscale)   [SR when RT off]
              → atmosphere, clouds, fog, bloom, exposure, tonemap, HDR
              → DLSS-G (MFG) + Reflex → present
   ngx/     FFM bindings to NGX C API (jextract), DLSS lib downloader/verifier
   pbr/     LabPBR atlases built parallel to vanilla atlas
   config/  presets + in-game screen
```

Key decisions:

1. **Ride Mojang's Vulkan backend, don't replace it.** Radiance ships its own
   C++ renderer (MCVR), which is why it lags MC versions. Hooking the vanilla
   backend keeps the hook surface small: when MC updates, only the mixins move.
2. **Hybrid, not pure ray-cast primary.** Raster G-buffer is cheaper, gives
   exact motion vectors for DLSS, and lets vanilla handle odd geometry. Rays
   are spent on lighting only.
3. **DLSS Ray Reconstruction is the denoiser.** No hand-written denoiser. RR
   with 1 spp GI is the single biggest quality/perf lever on RTX.
4. **Java + FFM for NGX, plus one tiny shim.** NGX's Vulkan entry points are
   `extern "C"`; the C++ helper header is thin inline wrappers we re-create in
   Java. The SDK only ships a static loader, so a ~50 KB relink of it (plus one
   C trampoline, see M0) goes in the jar. Nothing for users to compile.
5. **DLSS libraries downloaded on first launch** from the pinned NVIDIA/DLSS
   GitHub tag, SHA-256 checked, into `.minecraft/helio/ngx/`. Keeps the jar
   small, lets us bump DLSS without a mod update. Manual drop-in supported.
6. **Shaders: GLSL → SPIR-V at build time** (glslc via Gradle), hot-reload in dev.
7. **NVIDIA-only.** No FSR/XeSS/custom-denoiser fallback. Revisit only if
   anyone else needs it.

### Repo layout

```
helio/
  build.gradle.kts  settings.gradle.kts  gradle.properties   (Fabric Loom)
  src/main/java/<pkg>/helio/
    Helio.java  config/  vk/  ngx/  world/  render/  pbr/  mixin/
  src/main/resources/
    fabric.mod.json  helio.mixins.json
    assets/helio/shaders/
  docs/PLAN.md
```

## Milestones

Each has an exit criterion. Don't start the next until it's met.

### M0 — Feasibility spikes (de-risk before writing a renderer)

- Prism instance: 26.3 + Fabric, "Prefer Vulkan" on. Confirm stable on
  KWin Wayland at 1440p/180 Hz. Note baseline vanilla FPS at fixed seed/spot.
- Mixin into vanilla Vulkan device creation; add RT extensions + whatever
  `NVSDK_NGX_VULKAN_RequiredExtensions` returns + `VK_NV_low_latency2`.
- One ray-query compute pass (sun shadow for loaded terrain) composited over
  the vanilla frame.
- NGX via FFM: init, create DLSS-SR, evaluate on that frame.
- **DLSS-G natively on Linux Vulkan without Streamline** (Streamline is
  Windows-only). Create FG feature, get generated frames on screen with
  Reflex. Biggest unknown in the whole project.

Exit: written go/no-go for SR, RR, FG. If FG is no-go, the plan continues with
SR + RR + Reflex and FG stays behind an experimental flag.

#### M0 progress

Dev setup:
- Prism instance `Custom RT Mod 26.4` (folder name; game is 26.3), Fabric
  loader 0.20.1, Fabric API 0.165.0+26.3, `preferredGraphicsBackend:"vulkan"`.
- Build: `JAVA_HOME=~/.local/share/PrismLauncher/java/java-runtime-epsilon ./gradlew build`
  (Prism's Java 25 is a full JDK). Copy `build/libs/helio-*.jar` into the
  instance's `mods/`. Loom 1.19, Gradle 9.8.2, Mojang names (no Yarn on 26.x).

Vanilla Vulkan backend (`com.mojang.renderpearl.backend.vulkan`):
- `VulkanFeatureSets.optionalFeatureSets()` returns a mutable set. Mojang
  enables each optional `FeatureSet` (extensions + feature bits, pNext chain
  built for us) only if the GPU supports it. Hook: add ours at RETURN.
- `VulkanDevice` holds `VkDevice`, `vma`, graphics/compute/transfer queues
  and `enabledFeatures`. Hook: grab it at `<init>` TAIL.
- `VulkanGpuSurface` owns the swapchain (`acquireNextTexture`, `present`):
  the hook point for Reflex markers and DLSS-G.
- Device API version is Vulkan 1.2, so ray query also needs `VK_KHR_spirv_1_4`.
- Mojang's VMA allocator is created without the buffer-device-address flag.
  Plan: Helio uses its own VMA allocator for RT buffers rather than patching theirs.

Results:
- [x] **Device extensions** (2026-11-01): Mojang's log shows "Enabling optional FeatureSet
  [Helio ray tracing / Helio DLSS / Helio Reflex]" on driver 615.78.08,
  instance reaches the title screen, no new errors.
- [x] **NGX init + DLSS-SR** (2026-11-01): NGX initialises on Mojang's device and
  reports **SR, RR (`SuperSamplingDenoising`) and FG (`FrameGeneration`) all
  available** natively on Linux, driver 615.78.08, SDK 310.9.16. A 1280x720 to
  1440p DLSS-SR feature is created and released; the game reaches the title
  screen cleanly afterwards. Not yet evaluated on a real frame (that needs the
  ray-query pass's output).
  Three traps, all handled:
  1. The SDK ships NGX as a static library, so `native/build-ngx.sh` relinks it
     into `libhelio_ngx.so` (~50 KB, in the jar). Decision 4 becomes "Java +
     FFM, plus that one tiny shim".
  2. NVIDIA's driver library `dladdr()`s the address that called the SDK to find
     "the app". From FFM that is JIT code no file maps, so it aborts with
     `basic_string::_M_construct null not valid`. Fix: init goes through a C
     trampoline in the shim (`native/ngx_trampoline.c`). Same reason there is no
     NGX log callback (an FFM upcall stub). NGX writes `helio/ngx-data/nvngx.log` itself.
  3. NGX init puts a 1 MB buffer on the stack and Java threads get ~1 MB, so
     init on the render thread segfaults. Fix: `Helio.onBigStack` runs NGX
     calls on a 64 MB-stack thread while the render thread waits.
  Dev notes: a startup crash makes vanilla set `startedCleanly:false` in
  `options.txt`, then reset "Prefer Vulkan" and force OpenGL on the next
  launch. After a crash, set `startedCleanly:true` and
  `preferredGraphicsBackend:"vulkan"` again. NGX fatal errors are captured in
  `helio/ngx-data/stderr.log`. DLSS libs for testing are copied by hand from the
  SDK's `lib/Linux_x86_64/rel/` into `helio/ngx/` (~115 MB; downloader is M1).
  For release (M9): build the shim on an old-glibc image so it loads on older distros.
- [x] **Ray-query pass** (2026-11-01): ray-traced sun shadows drawn over the
  vanilla frame, checked in-game (taiga, low sun: trunk and canopy shadows on
  ground and water line up with the sun). `rt/SunShadowSpike.java` +
  `shaders/sun_shadow.comp`.
  - Hook: `LevelRenderer.render` right after `FrameGraphBuilder.execute`. We
    record into Mojang's own command buffer (`VulkanCommandEncoder.commandBuffer()`
    via accessor). Mojang keeps every image in `VK_IMAGE_LAYOUT_GENERAL` and
    puts a full memory barrier after each op, so we just add the same barrier.
  - Push descriptors (`VK_KHR_push_descriptor`, already required by Mojang):
    no descriptor pools, safe to rewrite every frame.
  - Depth is reversed-Z (`Projection` swaps near/far; clear value 0). Level
    projection (with view bobbing) captured in `GameRenderer.renderLevel`;
    world-space position = `inverse(proj * viewRotation)` + camera position. No Y
    flip needed.
  - Sun direction = `rotateY(-90°) · rotateX(sunAngle) · +Y`, from
    `SkyRenderState.sunAngle`.
  - Shaders compiled at runtime with the shaderc Minecraft already ships
    (SPIR-V 1.4, Vulkan 1.2 env). Hot reload later for free.
  - Own VMA allocator with the buffer-device-address flag; vanilla RGBA8 render
    targets get `STORAGE` usage via `VulkanConst.textureUsageToVk` (spike only).
  - Numbers: ~80–100k triangles for 128×128×96 blocks. **CPU meshing 65–105 ms
    (the hitch), GPU BLAS+TLAS build 1 ms** (15 ms on first build). So M2's
    problem is CPU meshing and upload, not GPU build time: mesh per chunk section
    off the render thread, one BLAS per section.
  - **Per-frame trace cost: 0.28–0.42 ms avg (max 0.88 ms)** for one shadow ray
    per pixel at native 2560x1440 (3.7M rays) against 57–90k triangles,
    measured with GPU timestamps around the dispatch. Game ran ~150–200 FPS
    meanwhile. These are coherent, first-hit rays, so an upper bound for the
    cheap case only. Bounce rays for GI are incoherent and cost several times
    more per ray, but at a DLSS internal resolution (about 1/3 the pixels) the
    M3 budget of a few ms for 2–4 rays/pixel looks realistic.
- [x] **DLSS-G presented natively** (2026-11-01): 2x frame generation in-game,
  **90 rendered → 180 presented fps** on the 180 Hz screen. User-confirmed
  smooth; usual FG ghosting on leaves; tiny rare stutters (likely the shadow
  scene's ~100 ms CPU rebuilds). `ngx/FrameGenSpike.java`,
  `shaders/camera_motion.comp`, `mixin/VulkanGpuSurfaceMixin.java`.
  - Raw NGX DLSS-G (no Streamline) only writes an interpolated image. Helio
    presents it: Mojang's blit/present shows the interpolated frame, then
    Helio acquires a second swapchain image, blits the real frame and presents
    it right after. FIFO (vsync) spaces them one refresh apart.
  - Inputs: backbuffer = vanilla main colour (GUI included, so HUD warps);
    depth = copy taken right after the level renders; motion vectors =
    camera-only, computed from depth + last frame's matrices (entities and the
    hand smear until M3 renders real motion vectors). JOML's column-major
    matrices go to NGX as-is. Depth inverted, camera motion included.
  - `DLSSG.MultiFrameCountMax` = **5**: up to 6x MFG on this GPU/driver/SDK.
  - Evaluate runs on the render thread without the 1 MB-stack problem; feature
    creation uses `onBigStack` to be safe.
- [x] **Reflex** (`vk/Reflex.java`): `VK_NV_low_latency2` rev 3 +
  `VK_KHR_present_id`. Latency mode on Mojang's swapchain, `vkLatencySleepNV`
  at the top of `Minecraft.runTick`, sim / render-submit / present markers,
  present ID on the real frame's present and on every `vkQueueSubmit2`
  (`VkLatencySubmissionPresentIdNV`; without it the driver's timings are garbage).
  With vsync the game is display-bound, so sleep alone changed nothing (33.3 ms
  either way). Adding NVIDIA's Reflex+VSync-style cap (just under
  refresh ÷ presents-per-frame) did: A/B in one session, input → GPU done
  median **33.3 ms off vs 24.1 ms on** (-27%). Cost: 87 instead of 90 rendered
  fps, so a few repeated frames per second at 180 Hz. M4 should pace from
  present timing (`VK_KHR_present_wait`, already supported) instead of a fixed 3% margin.
- Exit segfault (fixed in M1): DLSS-G's exit-time destructors called into
  Vulkan layers the loader had unloaded with the device. Releasing the FG
  feature and calling `NVSDK_NGX_VULKAN_Shutdown1` at `VulkanDevice.close`
  HEAD fixes it.
- Dev: `prismlauncher --launch "Custom RT Mod 26.4" --world "New World"`
  launches straight into the test world.

**M0 verdict: go on everything.** Vanilla Vulkan backend hooks, hardware ray
queries, DLSS-SR, Ray Reconstruction (available; evaluated in M3), Frame
Generation up to 6x, and Reflex all work natively on Linux with driver
615.78.08.

### M1 — Mod skeleton

- Loom project, mixin config, logging.
- Config: JSON (Gson, already in MC), presets Off / Performance / Balanced /
  Quality / Ultra / Photo, per-feature overrides, in-game screen (+ Mod Menu
  entry), keybind to toggle RT live.
- DLSS lib downloader + checksum + clear error UI.
- Startup self-check: GPU, driver version, required extensions. On any
  failure, fall back to vanilla rendering with a toast, never crash.
- Perf overlay: per-pass GPU timestamps, VRAM use, BLAS build queue.
- "Copy debug report" button (versions, extensions, config, last errors).

Exit: mod loads, toggles cleanly, survives missing DLSS libs/unsupported GPU.

**M1 progress:**
- Config `config/helio.json`; picking a preset fills the fields, editing one
  switches to Custom. Only settings with working features exist (RT, frame
  generation Off/2x, Reflex Off/On/Boost, overlay); upscaling, RR and ray
  budgets join in M3/M4, which is also when Balanced/Quality/Ultra diverge.
- Keys: H toggles ray tracing; "Helio settings" (unbound) opens the screen.
  Mod Menu is optional (`suggests`).
- DLSS libraries download from the pinned `NVIDIA/DLSS` tag on first launch,
  only when the device has the DLSS extensions, SHA-256 + size checked, with
  progress toasts. NGX comes up mid-session once they land. Any
  `libnvidia-ngx-dlss*.so.*` already in `helio/ngx/` counts (manual drop-in);
  `downloadDlss: false` turns the download off. Tested: 115 MB in ~6 s.
- Self-check toast on the title screen (not Vulkan / no RT / no DLSS / NGX
  failed). Settings greys out unsupported options with the reason; "Copy
  Debug Report" puts versions, GPU, driver, feature state, config and mods
  on the clipboard.
- Overlay (top right, hidden under F3): GPU ms per pass (`GpuTimers`),
  shadow scene size and rebuild time, FG rendered → presented fps, Reflex
  input → GPU latency, VRAM via `VK_EXT_memory_budget`. First reading:
  sun shadows 0.34 ms, DLSS-G 3.37 ms, motion vectors 0.04 ms, 1.6 GB VRAM.
  No BLAS queue yet: rebuilds are synchronous until M2.

### M2 — Scene geometry

- Chunk-section meshes → BLAS (opaque / cutout / translucent split), compacted,
  built on a frame budget, rebuilt on block updates.
- Opacity micromaps for cutout blocks (leaves, grass, flowers): kills most
  any-hit cost, the classic Minecraft RT bottleneck.
- Entities, block entities, held item, particles → per-frame dynamic BLAS
  (refit when possible).
- TLAS per frame. RT distance separate from render distance.
- Note: frustum/occlusion culling must not drop sections from the TLAS
  (shadows and reflections need off-screen geometry).
- Debug views: albedo, normals, BLAS heatmap, instance count.

Exit: stable TLAS while flying at speed; no hitching from BLAS builds.

**M2 progress:**
- [x] **Section BLAS from vanilla's meshes** (`rt/Scene.java`). No mesher of our
  own: a mixin on the section compile task copies each layer's vertices
  (vanilla BLOCK format, 28 B) into a BDA buffer on the compile worker, so every
  block model, fluid and 3D pack is in, and block updates arrive as recompiles.
  Vanilla's `RenderSection.reset` removes a section. One BLAS per section layer,
  sharing one quad index buffer (vanilla's 0 1 2, 2 3 0 order).
- [x] Render thread builds from the queue at most 256 BLAS / 256k triangles per
  frame, in Mojang's command buffer before the trace. Compaction 4 frames later
  (query → copy → deferred free). Freed GPU objects wait 4 frames.
- [x] TLAS every frame from all sections within the RT distance, visible or not,
  camera-section anchor for float precision. Vanilla only compiles visible
  sections, so `LevelExtractor` also offers it every section within the RT
  distance; its own dirty check compiles each once.
- [x] Masks: solid 1 (opaque), cutout 2 (alpha-tested against the block atlas in
  the ray query loop via instance → vertex-buffer address → UV), translucent 4
  (shadow rays skip it). Sun shadows moved onto the scene.
- [x] RT distance setting (4–32 chunks, capped by render distance).
- [x] RT distance fade: shadows fade over the last section instead of popping.
- [x] Debug view setting: BLAS Instances (primary rays, one colour per instance,
  magenta = miss).
- Bugs found in testing: LWJGL's `pGeometries()` doesn't set `geometryCount`
  (shared with `ppGeometries`), so every BLAS and the TLAS were empty until it was
  set by hand; compaction copies needed a barrier before the TLAS build; Reflex
  reused a present ID when a loading screen presented twice in one frame.
  Testing note: Prism keeps instance settings in memory and rewrites
  `instance.cfg`, so edit it only with Prism closed. Under the validation layer
  `vkLatencySleepNV` blocks and DLSS-G feature creation segfaults; test those
  without it.
- Dev flag `-Dhelio.flyTest=true`: flies east at 40 blocks/s.
- Reading, 12-chunk render distance, taiga spawn: 9553 BLAS, 7.35M triangles,
  5122 sections, 652 MB (vertices + compacted BLAS); steady scene update
  0.1–0.2 ms GPU.
- [x] **Fly test (user, 2026-11-01):** queue rises and drains instantly, no
  hitches, no missing shadows, block updates instant, no crashes. Exit criterion
  met for terrain.
- [x] **Entities, block entities, dropped items, armour, moving blocks**
  (`rt/Entities.java`): tagged by render type when vanilla stages the level's
  feature draws, positions copied at upload (camera-relative, so only the level's
  `prepareFrame`; the first-person hand and GUI items are separate frames), one
  opaque BLAS rebuilt per frame (PREFER_FAST_BUILD), mask 8. Overlays and decals
  (glint, trims, eyes, blob shadows, outlines, text) skipped. User-confirmed
  shadows from a fox, chest and armour stand. Not yet: entity texture alpha
  (needs bindless textures, M3), particles, the player's own body in first person.
- [x] **Opacity micromaps** (`rt/Micromaps.java`, `shaders/omm.comp`): cutout
  layers get a 4-state, level-4 micromap (256 micro-triangles, 64 B per
  triangle) classified on the GPU from the block atlas; mixed micro-triangles
  keep the alpha test. Micro-triangle order from NVIDIA's vk_mini_samples
  `mm_opacity/bird_curve_helper.cpp`. **A/B in a dense forest (user):** sun
  shadows 0.78 ms on vs 0.75 ms off, scene memory 1256 vs 628 MB. One coherent
  first-hit shadow ray per pixel never paid much for alpha tests. **Re-test in
  M3 with reference-mode bounce rays, same forest (user):** path tracing 6.41 ms
  on vs 9.50 ms off (about 30% faster), scene memory 1219 vs 869 MB. Incoherent
  rays through leaves are where alpha tests hurt, and M4's real-time GI shoots
  them every frame, so OMM is now **on by default** (`opacityMicromaps` in
  `helio.json`, startup only). If memory gets tight (M9 budgets), drop to level 3
  (16 B per triangle) and use special indices for fully opaque or clear triangles.
- [x] **Debug views** (Debug View setting; primary ray per pixel, magenta = miss):
  BLAS Instances, Albedo (atlas × vertex tint, vanilla AO still baked in;
  entities grey until their textures are bound), Normals (geometric), Triangle
  Density (hit BLAS triangle count, log scale). Per-instance shader info is now
  16 B: vertex address, triangle count, kind. User-checked: all line up with the
  world; magenta only beyond the RT distance.
- **M2 done (2026-11-01).** Particles and first-person body shadows deferred.

### M3 — Path tracer core

- G-buffer + motion vectors (incl. entities, animated textures).
- Sun/moon direct light, soft RT shadows.
- Emissive blocks: light list from block light level, colour from texture;
  ReSTIR DI to sample many lights.
- Indirect: 1–2 bounces, spatial hash radiance cache (SHaRC-style) for the tail.
- Water / glass / ice: RT reflection + refraction. Translucency ordering.
- Hybrid preset: RT shadows + reflections only, raster-style ambient (perf mode).

Exit: daytime overworld, cave with torches, and a lava-lit Nether scene all
look right with accumulation on (reference) and stable with 1 spp.

**M3 architecture (decided 2026-11-01):**
- **Where:** inside vanilla's `main` frame pass, after the solid render pass
  (opaque terrain + solid entities) and before translucents, clouds, weather and
  particles. Helio's compute passes overwrite the main colour with path-traced
  radiance; vanilla then draws transparent things on top with its own depth.
  Improved Transparency (OIT) already ends the solid render pass there; with
  classic transparency, Helio skips `executeClassicTransparency` inside the solid
  pass and replays it in a fresh render pass after its own work.
- **Primary visibility:** ray-traced from the camera (the debug views prove the
  G-buffer path), not read back from vanilla's raster. Vanilla depth stays as is
  for translucent sorting.
- **Albedo:** atlas × vertex tint for terrain. Vanilla bakes AO into the vertex
  colour; strip it by recomputing the tint per face (or compiling sections with
  AO off for Helio's copy). Entities need their own textures: capture the
  render type's Sampler0 per draw into a bindless array and keep UVs.
- **Output:** linear HDR radiance in a Helio image, then a simple tonemap into
  the main colour until M6's post chain. Temporal accumulation while the camera
  is still (the reference mode); DLSS RR replaces it in M4.

**M3 steps:**
1. [x] Hook point + skeleton (`rt/PathTracer.java`, `shaders/pathtrace.comp`):
   primary rays, sun with a shadow ray, flat sky ambient scaled by vanilla's sky
   light, block-light stand-in from vanilla's light level, ACES fit tonemap into
   the main colour. Runs at `executeOit` HEAD (Improved Transparency) or, with
   classic transparency, holds `executeClassicTransparency` back and replays it
   in its own render pass after tracing. M0 shadow overlay retired. Entities keep
   vanilla shading darkened in shadow until step 4. View bobbing translates
   inside vanilla's projection, so rays unproject the near plane as origin
   (user-confirmed: picture stays locked). 1.0 ms at 1440p native over 7M
   triangles, 1.8 ms in a cave over 11M. User-checked: daylight, cave with
   torches, water/glass/entities drawn over it by vanilla, no edge artifacts.
   Known: no shadows on water (vanilla draws it; step 7). No separate HDR target
   yet (writes tonemapped colour directly); accumulation in step 2 needs one.
2. [x] Accumulation: linear radiance + coverage averaged in an RGBA32F image,
   tonemapped and composited into the main colour in the same shader (coverage
   blends with vanilla at sky / RT-distance edges). "Reference Accumulation"
   setting (off by default) jitters primary rays (Halton 2,3) and averages;
   restarts on view change, sun jump (>0.05 rad) or a rebuild within 2 sections
   of the camera, otherwise adapts (cap 256 frames). User-checked: climbs to
   256. **Primary rays trace terrain only now:** vanilla's solid features
   (entities, opaque particles, sign text, overlays) are held back from the
   opaque render pass and replayed over the traced terrain (they had been
   painted over). Entities show vanilla shading again but still cast RT shadows.
   Motion vectors moved to step 4: with camera motion only they'd equal the
   current depth-based ones.
3. [x] Sun colour from air mass (reddens near the horizon), dimmed by rain;
   moonlight with its own shadow ray at night; sky light from vanilla's sky
   (zenith) and fog (horizon) colours by normal, half desaturated, with a moonlit
   floor at night; no-sky dimensions get a dim fog-tinted ambient. Soft shadows
   sample a 0.6° disc only with Reference Accumulation (1 spp flicker until M4's
   RR). Sky light is still scaled by vanilla's sky light level, not traced (step
   6). User-checked: day, sunset, night, rain, soft shadows.
4. [x] AO-free albedo: while sections compile for the path tracer,
   `BlockModelLighter` output is reset to white, so vertex colour is the biome
   tint only (light levels kept); toggling RT recompiles everything
   (`LevelExtractor.allChanged`). Entities: each surface draw's `Sampler0`
   texture is captured (≤256 per frame, bindless set 1) with full vertices
   (BLOCK layout + texture index, 32 B); the dynamic BLAS is alpha-tested;
   entities are traced and lit like terrain, also past the RT distance.
   Vanilla still draws entities (their depth keeps water/glass behind them);
   `entity_composite.comp` then puts the traced colour back where vanilla's
   depth matches the traced hit (0.3%), so particles and name tags in front
   stay. Known: coplanar overlays (glint, trims, spider eyes) are covered;
   with classic transparency, water in front of an entity is overwritten.
   User-checked: entities vs water/glass, name tags, H toggle. **Entity motion
   vectors moved to M4** (DLSS RR needs them with its other guide buffers).
5. [x] Block lights (`rt/Lights.java`): emitters collected per section on the
   compile worker, only if a neighbour lets light out (buried lava halved the
   count, 53.7k → 26.7k); colour = mean of the brightest quarter of the particle
   sprite's opaque texels (`SpriteContents.originalImage`). Per frame a table of
   the sections within the RT distance is uploaded. RIS (ReSTIR's first stage,
   no reuse yet): 8 candidates from the 27 neighbouring sections (section ∝ light
   count / distance², light uniform), inverse-square windowed to 15 blocks, one
   shadow ray to a point in the chosen block that stops at its box. Skipped where
   vanilla's block light is 0. Emissive surfaces: emission level in vertex alpha
   (255 − level; `BlockModelLighter` / `FluidRenderer` hooks), radiance =
   texture × 4. A dim fill from vanilla's block light level stands in for bounce
   light until step 6. Without accumulation the random stream is fixed per pixel
   (still grain, no flicker). User-checked: torches, lantern, soul lantern
   (cyan), glowstone, lava lake; grain much lower after culling. Cost: ~7 ms at
   native 1440p in a lava-lit cave (incoherent shadow rays); M4 runs at DLSS
   internal resolution. Bloom around emitters is M6. ReSTIR temporal/spatial
   reuse: M7 if RR alone isn't enough.
   Also fixed: `Memory.buffer` now over-allocates 255 B and aligns the address to
   256 itself (VMA's aligned create isn't in LWJGL 3.4.3), recording the offset
   for AS/micromap creation; a 32-aligned light table had disabled the tracer.
6. [x] Indirect light, **reference mode only** (Reference Accumulation on):
   up to 3 cosine-sampled bounces (Russian roulette after the 2nd), direct sun/
   moon + block-light sampling at every bounce, escaped rays bring the sky;
   emitters hit by bounces add nothing (light sampling already counts them).
   Replaces the vanilla-sky-level and block-light-fill approximations there;
   outside reference mode those stay until M4's RR. User-checked: red bleed on
   white concrete, corner darkening, cave and forest. Cost: 8.5 ms outdoors,
   29 ms in a lava/lantern cave at native 1440p (reference only). The
   SHaRC-style radiance cache moves to M4 with real-time GI under RR.
7. [x] Water / glass / ice: primary rays now include the translucent layer, and
   vanilla skips translucent sections inside the RT distance
   (`Scene.tracesTranslucent`, wrapping `SectionMesh.getSectionDraw` in
   `extractSectionDrawGroups`); past it vanilla draws them and rays look through.
   Each surface: Schlick Fresnel reflection (first surface only: one reflection
   ray per pixel; sky gradient plus sun/moon glint when it escapes), its texture
   lit like any surface (water at 30% of its alpha), the rest transmitted. Water
   (vertex alpha marks it as "emission 1", `Scene.WATER_MARK`) refracts at 1.33
   with total internal reflection, absorbs per block as a power of its biome
   tint, and glows with the sky/sun reaching it; the camera underwater starts
   in water with vanilla's fog colour as the glow. Glass and ice are thin sheets.
   Water now gets shadows. The entity composite also restores the traced colour
   behind traced water/glass (fish, mobs behind windows). Also fixed: water in
   waterlogged emitters (sea pickles) no longer glows. User-checked: lake with
   reflections and refracted sand, shoreline, ice. Known: opaque particles and
   sign text behind traced water/glass are hidden; reflections ignore other
   translucents; no coloured shadows through stained glass yet (M6).
8. [x] Hybrid lighting (`HelioConfig.Lighting`, "Lighting" setting; the Performance
   preset uses it): ray-traced sun/moon shadows and reflections (water, glass,
   metal, gems; refraction kept), block and sky light from vanilla's light
   levels, no random sampling, so no RR. User-checked: 30 -> 75 fps base
   (Hybrid tracing 7.6–11.4 ms at native 1440p), everything else as Path
   Traced. Known: aliased edges (no jitter, no denoiser): DLAA/SR under Hybrid
   comes with M4 step 2.

**M3 exit check:** daytime overworld, torch-lit cave and water/glass scenes
user-checked in reference mode and under RR. Still to see: a lava-lit Nether
scene.

### M4 — DLSS integration

- DLSS RR for RT path (quality modes, DLAA). DLSS SR for non-RT path.
- Reflex via `VK_NV_low_latency2` (markers, sleep).
- MFG 2x/3x/4x (+ higher modes if the SDK enables them on Blackwell), with
  UI/HUD handled correctly (render HUD after FG input or use the
  UI-recomposition path).
- Presets map to DLSS modes. Optional dynamic resolution.

Exit: 1440p, RR Balanced, base ≥ 60 FPS in the M3 test scenes; with FG,
output near 180 Hz and no HUD smearing.

**M4 steps** (planned 2026-11-02; Reflex and 2x FG already run from M0/M1):
1. [x] Ray Reconstruction at native resolution (DLAA, `ngx/RayReconstruction.java`,
   `shaders/rr_resolve.comp`): stochastic sampling every frame (soft shadows,
   light RIS, 1 GI bounce); guides (diffuse/specular albedo, world normal +
   roughness, HW depth, camera motion in pixels); pixels left to vanilla go in
   as vanilla's colour sampled at the ray jitter (bilinear), and the sky keeps
   vanilla's own pixels after RR (its hard, near-white sun flickered through
   RR). DLSS jitter is the negated sample offset. Reference Accumulation
   overrides RR; "Ray Reconstruction" toggle in the settings. Cost at native
   1440p: RR ~10.5 ms, tracing 8–12 ms (step 2 cuts both).
   Done alongside, user-checked:
   - **Indigo:** Fabric API's renderer meshes terrain instead of vanilla's
     BlockModelLighter, so no block hook had ever run (AO was baked in, block
     emission and every mark missing). `IndigoBlockRendererMixin` undoes
     Indigo's shading and writes `Scene.mark` into vertex alpha. M8: Sodium
     replaces Indigo and needs its own hook.
   - **Marks** (vertex alpha = 255 − mark): emission 0–15, water 1
     (translucent), foliage 1 (cutout), plain glass 2, metal 32, gem 33.
     "Materials" debug view shows what the shader reads.
   - **Foliage** (leaves/plants by tag) transmits: back-lit sun at 50%, bounce
     rays pass through.
   - **Glass:** stained glass, ice and plain glass (translucent in 26.3,
     `force_translucent`) refract at 1.5 with the medium tracked across
     surfaces; shadow rays take a second translucent-only trace and multiply
     each surface's colour (coloured light through stained glass).
   - **Polished blocks:** metals reflect in their colour (GGX 0.2, 30%
     diffuse), gems faintly and sharply (F0 0.08).
   - **Entities:** edge pixels take the traced colour via neighbouring
     pixels' entity depth (jittered rays missed edges vanilla's raster hit).
     The first-person body is extracted and submitted with renamed render
     types vanilla skips drawing; it casts shadows, camera rays pass through.
   - Reflected sun/moon match vanilla's squares; first-launch freeze fixed
     (pipeline compiles on the render thread).
2. [x] Internal resolution (`ngx/Dlss.java`, was RayReconstruction): the tracer
   renders at DLAA / Quality / Balanced / Performance / Ultra Performance size
   ("DLSS" setting, presets map to it); vanilla's colour and depth are sampled
   at the matching window position; hit depths for the entity composite go
   through a render-size image. RR for Path Traced, DLSS SR for Hybrid (fixes
   its aliasing) and with RR off. Jitter: 8 phases per covered output pixel.
   User-checked at 1440p Balanced (1488x837): Path Traced 30 -> 65 fps base
   (tracing 5.7 ms, RR 4.5 ms), Hybrid 87 fps (2.2 ms + SR 1.5 ms), anti-
   aliasing visible. Not done: DLSS with ray tracing off (needs Mojang's
   render targets at render size), deferred.
3. [x] Entity motion vectors: entity vertices carry a motion offset (last frame's
   camera-relative position minus now's), matched vertex for vertex when a
   captured draw has last frame's texture and vertex count (vanilla keeps draw
   order stable; capture runs before translucent sorting), else camera-only.
   DLSS gets it per pixel; `entity_motion.comp` writes it into frame
   generation's motion at entity pixels. User-checked: no trails.
4. [x] Radiance cache (after NVIDIA's SHaRC; `shaders/radiance_cache.comp`): a 2M-entry
   world-space hash grid (64 MB) of the light reaching faces, direct and bounced, without the
   face's albedo. Cells are 1/4 block, doubling with camera distance past 8 blocks up to 16;
   one per face direction. Under Ray Reconstruction each pixel traces one bounce and takes the
   light where it lands from the cache (no shadow rays there); one pixel in 16 follows 3
   bounces and adds what reached each surface to its cell. A pass after tracing averages the
   samples (history of 128) and frees cells unfed for 64 frames. The cache empties on a level
   change. Uncached cells fall back to direct light plus the old sky fill. Reference
   accumulation and Hybrid are unchanged. User-checked: coloured bounce light (stained-glass
   light tints the ceiling).
   Done alongside: "Cave Brightness" setting (0–100%, default 25%). It scales the Brightness
   floor down where vanilla's sky light is 0, blending by sky level. User-checked.
5. [x] Reflections and transparency under RR: the reflection ray's hit distance plus
   world-to-view / view-to-clip matrices let RR move reflections. Where the first water /
   glass / ice surface is mostly seen through (transmitted share over Fresnel), RR's depth
   and motion guides sit on what's behind it, at its distance along the unbent view ray
   (primary surface replacement, as Portal RTX does). The surface normal stays, for the
   reflection. Sky behind glass is put far away. No transparency guide: vanilla draws
   particles after the tracer and DLSS, so they never pass through RR. User-checked: no
   smearing behind water and glass.
6. [x] Multi frame generation 3x/4x, HUD after FG input, presets map to DLSS
   modes; M4 exit check. In progress:
   - [x] 3x/4x (`DLSSG.MultiFrameCount` = generated, `MultiFrameIndex` 1..N, one
     evaluate each). Setting offers Off..4x (capped by `mfgMax`); presets:
     Performance 2x, Balanced/Quality 3x, Ultra 4x, Photo off.
   - [x] HUD: DLSS-G gets the HUD-less picture (main colour copied after the level,
     before hand and GUI), so the HUD comes from the real frame.
   - [x] Presenting (`ngx/PacedPresenter.java`): while FG runs Mojang skips its
     acquire/blit/present; the pacer acquires, copies (from per-frame staging images
     the render thread blits on Mojang's queue) and presents every frame just in time,
     on a queue of its own: an extra graphics-family queue (`VulkanBackendMixin`; the
     async compute family can't present on Wayland). Fixed: 3x/4x swapchain starvation
     and deadlock (up-front acquires), GPU stalls behind Mojang's acquire wait, frames
     dropped by mailbox-style presents (presents kept a refresh apart), leaked images.
     The real frame is presented on Mojang's queue with the Reflex present ID.
   - [x] Reflex: the driver's latency sleep goes by the game's own acquires/presents
     and slept the game to half speed under FG; while FG runs Helio limits instead
     (one frame queued, rendered rate capped at refresh / presents, 5% under).
   - User-checked (180 Hz, VRR off): generated frames shown correctly; same scene no
     FG 70 fps, 2x 55 -> 112, 3x 50 -> 150, 4x 43 -> 171 (cap). DLSS-G GPU time
     3.1 / 4.5 / 6.6 ms is the drop. VRR on flickered (monitor 50-180 Hz).
   - [x] Tried and reverted: DLSS-G on the present queue, overlapping the next frame's
     tracing. 2x unchanged, 3x/4x worse (4x 20 fps base): that queue is in the graphics
     family, so it time-slices with tracing instead of running alongside, and the single
     pacer thread can't start a frame's evaluate until the last frame's presents are out.
     M7: retry on the async compute queue (family 2, if DLSS-G accepts it), with a
     separate thread for presents.
   - [x] M4 exit check. First run (1440p, Balanced + RR, 3x): overworld 72 / 50 -> 150,
     torch cave 50 / 40 -> 119, water + glass 95 / 57 -> 171, lava Nether 50 / 39 -> 116
     (FG off / 3x rendered -> presented); HUD smears with FG. Fixes so far:
     - Radiance cache fed by whole 8x8 groups (1 in 16) instead of scattered pixels: one
       feeding pixel made its whole lockstep group wait for a 3-bounce path. Nether path
       trace ~15 -> 6.6 ms.
     - Block light RIS: 4 candidates when stochastic (was 8), bisection over the section
       cdf. Nether 6.6 -> 4.2 ms. Nether now 97 fps FG off, 2x 70, 3x 57, 4x 43 (cap).
     - Frame generation feeling under 80 fps at "140": Mojang's swapchain image was leaked
       each time FG started (Mojang presents that first frame now; tested with 30 starts).
       Each frame's copies are queued on the GPU before it finishes and presents are timed
       from when the first image is ready (0.67 ms after the frame), on a top-priority
       queue. Readback check confirmed generated frames sit halfway between real ones.
       User-checked: smooth at 2x.
     - HUD smearing: vanilla's vignette darkens the whole picture after the HUD-less
       copy, so DLSS-G took the entire screen for HUD (its indicator: Hudless yes, UI
       recomposition off; that needs a separate UI + alpha layer). Vignette skipped
       while FG runs (`HudMixin`), and `hud_keep.comp` puts the real frame's pixels back
       over each generated frame wherever it differs from the HUD-less copy (+1 px):
       HUD, hand, crosshair. Mask checked on screen. User-checked: HUD sharp.
     - Torch cave re-check: 97 fps FG off; base 70 (2x), 57 (3x), 42 (4x).
     - Passed: base >= 60 FG off in all scenes (overworld 72, cave 97, water + glass
       95, Nether 97); with FG, 3x/4x reach 168-171 at 180 Hz; HUD sharp. M4 done.

### M5 — PBR and 3D resource packs

- LabPBR 1.3: `_n` (normal, AO, height) and `_s` (smoothness, F0/metal IDs,
  porosity, SSS, emission) stitched into atlases matching the vanilla atlas.
- Hardcoded metals (F0 IDs 230–237), SSS for leaves/foliage, emissive maps
  feeding the light list.
- 3D packs: these are just block-model geometry, so they work through M2;
  verify BLAS cost and OMM for their alpha-tested parts.
- Sensible defaults when a pack has no PBR maps.

Exit: 2–3 popular LabPBR packs (one 3D) render correctly; reload with F3+T.

**M5 steps** (planned 2026-11-14). Test pack: SPBR 22 (LabPBR, vanilla-style, 26.3;
in the Radiance Test instance) plus its add-ons (emission, glowing ore, mirror).
1. [x] PBR atlases: on every block-atlas upload (`TextureAtlas.upload`, so F3+T too),
   build a normal atlas and a specular atlas the size of the block atlas. Each sprite's
   `<name>_n.png` / `_s.png` from the resource manager, scaled to the sprite's size,
   first frame if animated; LabPBR defaults where a pack has none (flat normal, AO 1;
   specular "no data", so Helio's own metal/gem marks still apply). Bound to the path
   tracer; debug views for both.
   - Done (`rt/PbrAtlas.java`, `TextureAtlasMixin`): SPBR gives 1103 normal and 1264
     specular maps into 2048x2048 atlases in 90 ms. Debug views (PBR Normal / Specular
     Map) line up with the textures. Not used for shading yet.
2. [x] Normal maps: tangent frame per triangle from its UVs (no vertex tangents),
   LabPBR normal (RG, Z rebuilt) bends the shading normal: lighting, bounces, RR's
   normal guide. Geometric normal kept for ray offsets and leaks. Texture AO (B) on
   ambient and bounce light.
   - Done in `pathtrace.comp`: `Hit.shading` (normal-mapped, kept on the ray's side)
     for sun, block lights (only from in front of the real face), sky fill and RR's
     normal guide; `Hit.ao` on ambient, sky fill and bounces. LabPBR normals are
     DirectX-style (+Y down the texture), checked against SPBR's height maps.
     The first bounce leaves around the shading normal too (mirrored above the real
     face). Normal Map Strength setting (default 300%: SPBR's bumps were barely
     visible at 100%).
3. [x] Specular: smoothness -> roughness, F0 (0-229 dielectric), hardcoded metals
   230-237 (iron, gold, aluminium, chrome, copper, lead, platinum, silver: real
   N/K), 238-254 albedo metals. One GGX reflection ray per surface below a roughness
   cutoff, diffuse for the rest. Pack data replaces Helio's metal/gem marks.
   - In `pathtrace.comp` (`Hit.f0/roughness/diffuse/specular`): roughness = 1 - R,
     F0 = G/255 below 230, table F0 for 230-237, albedo for 238+; metals have no
     diffuse and always get a ray, dielectrics below roughness 0.6. Reflection lobe
     around the shading normal, mirrored above the real face. Fresnel: Schlick with
     the grazing rise capped by roughness. In SPBR about half the textures are smooth
     enough (planks, polished stone, diamond), 290 have metal texels.
4. [x] Emission (`_s` alpha): emissive texels glow and count in the block's light
   (Lights.java samples the texture already); emissive ores etc. become light sources.
   - In `pathtrace.comp`: _s alpha (0-254, 255 none) masks light blocks' glow (SPBR's
     torch: only the 4 flame texels glow, not the stick); blocks that give no light
     (glowing ores) glow at `PACK_GLOW` (half an emission-15 surface) and light their
     surroundings through bounce rays and the radiance cache (`glow()`), not the light
     list: vanilla's light level gates light sampling, and ores have none.
   - SPBR add-ons (GlowingOre, BlockEmission) needed their pack.mcmeta rewritten for
     26.3 (min_format 65); done on the test instance's copies only.
5. [x] Subsurface (`_s` B 65-255) on leaves and plants in place of the fixed foliage
   transmission; porosity (B 0-64) stored for M6 rain.
   - In `pathtrace.comp`: `Hit.transmission` = (B - 65) / 190 for foliage with pack
     data (SPBR leaves 0.56, vines 1.0), `FOLIAGE_TRANSMISSION` (0.5) without; drives
     sun from behind and which side bounces leave from. Porosity isn't stored: M6 reads
     it from `pbrSpecular.b` (B / 64 below 65) when rain needs it.
6. [x] Animated sprites: PBR maps follow the atlas animation frames.
   - `PbrAtlas`: animated maps' frames go into strip textures; `TextureAtlas.
     cycleAnimationFrames` (TAIL) advances a frame clock built from the texture's
     .mcmeta like vanilla's (both restart at upload) and copies changed frames into
     the PBR atlases. No interpolated frames (ponytail). SPBR: 13 animated maps (water,
     nether portal, sculk family, respawn anchor, firefly bush); its lava, fire and sea
     lantern maps are single frames. Water's normal map isn't used for its reflection
     and refraction yet (M6 water waves).
7. [x] 3D pack check: BLAS size, build time and OMM on a 3D pack.
   - 3D Vanilla v1.20 (Modrinth, 26.3) over SPBR: scene 7.3M -> 76-78M triangles,
     scene memory 0.95 -> 6.2 GB, VRAM 13.2 / 14.9 GB; path trace about the same
     (8.99 vs 9.49 ms, different spots), incremental build 0.4 ms. The triangles are
     visible geometry: short grass is 360 quads per plant (340 without cullface), oak
     logs 118, some decorative blocks over 1000. Skipping blocks boxed in by solid
     cubes barely helped (78.0M -> 75.9M) and was reverted.
   - Scene memory has no budget; a heavier pack or a longer RT distance can run out
     of VRAM. Moved to M7 (VRAM budget).
8. [x] Exit check: SPBR + one more LabPBR pack + one 3D pack; F3+T reload; performance
   against M4 numbers.
   - rotrBLOCKS V88 [3D Foliage] 128x (Modrinth, 26.3): renders right; PBR atlases
     16384x8192 (1096 normal, 1043 specular maps) in 0.6 s. Same spot as SPBR: path
     trace 22.7 ms (34 fps) vs 5.1 ms (87 fps), 13.7M vs 6.6M triangles. Experiments:
     coarse texture mips 22.7 -> 17-20 ms; cutout treated as solid 22.7 -> 1.9 ms. The
     cost is alpha tests on rays through its 3D leaves: opacity micromaps are level 4
     (256 micro-triangles per triangle, sized for 16x), so on 128x leaves nearly every
     micro-triangle is mixed and falls back to the shader's alpha test.
   - Found: opacity micromaps never took effect. Ray queries only use them when the
     shader declares RayTracingOpacityMicromapKHR, which glslang emits only when the
     shader references gl_RayFlagsForceOpacityMicromap2StateEXT (now `OMM_FLAGS`, a
     no-op 0, under `HELIO_OMM`). Micromaps reworked too: one entry per unique UV
     triangle in the layer (deduped on the compile worker), subdivided to about a
     texel per micro-triangle (levels 1-7), with a per-triangle index buffer. Checked:
     with every shader-tested cutout hit dropped, leaves still show (the hardware
     commits their opaque parts).
   - Same spot, old -> new: SPBR 4.15 -> 4.03 ms, rotrBLOCKS 33.9 -> 32.3 ms.
   - The real rotrBLOCKS cost: in 26.3 each quad takes its layer from its texture's
     transparency, and its 128x leaves have soft (partly transparent) edges, so they went
     to the translucent layer. There Helio's foliage mark equals the water mark: the leaves
     were traced as water (refracting layer after layer, shiny like water, skipped by
     reflections as water). `SectionCompilerMixin` now sends foliage's translucent quads to
     the cutout layer while the path tracer meshes. Frozen-lake spot: 22.5 -> 8.4 ms; SPBR
     unchanged (7.1 ms).
   - Reflections now show stained glass, ice and nether portals (blended over what's
     behind them, one layer): `reflectionSeen` in pathtrace.comp. Water stays out of them:
     translucent layers that are only water lack the new `MASK_NOT_ONLY_WATER` instance
     bit, and the any-hit skips water in mixed ones.
   - First-person body: reflection rays skip it too (`reflectionRay`); a shiny floor
     showed the head the camera sits in.
   - F3+T (user, 2026-11-14): SPBR reloads clean; switching to rotrBLOCKS and back too.
   - Performance (user, SPBR, 1440p Balanced + RR, FG off): overworld 72 (M4 72), torch
     cave 100 (97), Nether 89 (97), water + glass 45 (95). The water + glass number was
     taken at another view than M4's: at the saved spot, every build since M4 measured
     back to back gives path trace 11.6-11.8 ms (M4) -> 14.1 (step 3) -> 14.0-15.1 ms now
     (DLSS 4.4 ms on top). The ~2.5 ms is step 3's specular reflection rays (SPBR makes
     many surfaces smooth enough for one); moved to M7. Scene coordinates were never
     written down: record them (F3) at the next performance check.
   - Packs checked: SPBR 22 (+ BlockEmission, GlowingOre), rotrBLOCKS V88 3D Foliage
     (LabPBR, 3D), 3D Vanilla v1.20 (3D). M5 done.

Fixed along the way: weather, clouds and translucent particles vanished in front of
traced water and glass with classic transparency. The entity composite (traced colour
back wherever vanilla's depth lies behind the water) ran after the replayed classic
translucents, which write no depth; it now runs between solid features and translucents.
Also: with no PBR pack, lakebeds and leaves showed a block-by-block light/dark checker. `PbrAtlas.FLAT_NORMAL`
(the normal map of sprites a pack doesn't cover) was in ABGR order where NativeImage.setPixel takes ARGB:
every such texture got a normal lying flat along its U axis (x3 by Normal Map Strength) and AO 0.5, so faces
whose textures vanilla rotates at random turned bright or dark per block. Found on the way: sun and block
light reached lakebeds through water unabsorbed (shadow rays rising through water are now absorbed up to its
nearest surface by the biome's water tint, as view rays are), and ice (`Scene.ICE_MARK`) no longer tints the
light under it (vanilla's texture made it a strong blue filter).

### M6 — Visuals beyond ray tracing

- Physically based sky (transmittance / multi-scatter LUTs), sun/moon disc,
  stars; separate Nether and End looks.
- Volumetric clouds; ray-traced volumetric fog and god rays.
- Weather: wet surfaces + puddles from rain (via porosity), snow.
- Water: waves, caustics, underwater fog.
- Post: auto exposure, bloom, AgX tonemap, colour-grading LUT, optional
  film grain / vignette.
- SDR output only (the user's monitor has no HDR); lighting stays HDR internally.

Exit: side-by-side screenshots you're proud of, in SDR.

**M6 steps** (planned 2026-11-14). Today: one fixed exposure and Narkowicz's ACES fit (pathtrace.comp,
rr_resolve.comp); vanilla draws the sky, sun, moon, stars and clouds, and the tracer's own sky light is a
blend of vanilla's sky and fog colours (`skyIrradiance`, `skyRadiance`) with a fitted sun (`sunRadiance`).
Test pack: SPBR (memory: rotrBLOCKS only when a test needs it).
1. [x] Exposure and tonemap: auto exposure from the frame's average log luminance (after RR, adapting over
   about a second, kept within a few stops of daylight so caves stay dark); AgX in place of ACES, with an
   Exposure setting (EV offset). Vanilla's pixels (sky, past the RT distance) still come out as vanilla drew
   them: their radiance for RR is the inverse of tonemap and exposure.
   - Done in code: `pathtrace.comp` meters every 8th traced pixel's log2 luminance into a device-local slot
     (`Meter`), copied into the frame's data; `PathTracer.adaptExposure` reads it when the ring comes back
     around, aims at EXPOSURE_KEY 0.07 (snowy daylight lands near 1x), within +-2 EV, tau 0.8 s, times the
     Exposure setting (-2..+2 EV). AgX (Wrensch's fit) with a 1.2 saturation look in both shaders; `untonemap`
     inverts it exactly (curve by bisection). Summing with subgroupAdd after the ray queries hung the GPU
     within a minute (5 s semaphore timeout); plain atomics from the sparse grid don't (200 s soak FG off, 180 s
     FG 2x). Same daylight view: about the ACES picture's brightness, softer highlights.
   - Later (user): the auto exposure lifted moonlit nights 2 stops, to dusk. Its key now scales with time and
     dimension: overworld 15% at night up to 100% by day (`daylight`), Nether 50%, End 35%. Midnight: +2.0 EV
     before, +0.1 after. A cave at night gets the night key too (ponytail: no indoor/outdoor test).
2. [x] Bloom: from the HDR picture after RR, before the tonemap (emissive blocks, sun glints, the sun).
   - Done in code: `rt/Bloom.java` + `bloom.comp`: 6-level chain from DLSS's output (half size first),
     Jimenez's 13-tap down (Karis-weighted first pass), 3x3 tent up added level by level. `rr_resolve.comp` mixes
     the Bloom setting's share of it (default 10%: 5% looked too faint, 15% too strong) into the radiance before exposure and AgX; sky pixels too (vanilla's pixel through
     `untonemap`), so the vanilla sun gets a halo. Not in reference accumulation (no DLSS).
3. [x] Physically based sky: transmittance, multi-scattering and sky-view LUTs (Hillaire 2020) from the sun
   angle; the tracer draws the sky (vanilla's square sun and moon kept as sprites, stars), sun light comes
   through the transmittance LUT, sky light from the sky-view LUT. Rain and thunder darken it; Nether and End
   keep fog-coloured skies, tuned.
   - Done in code: `rt/Sky.java` + `sky.comp`: transmittance (256x64) and multiple-scattering (32x32) LUTs once;
     each frame a sky view (256x128, world azimuth, both sun and moon) and a 6-face ambient cube from it.
     pathtrace.comp: sun and moon light through the transmittance LUT (SUN_TOP 2.9 puts about 2.6 on the ground
     at noon), sky radiance from the sky view, sky light from the ambient cube, stars that turn with the sun,
     rain greys it. Helio draws the sky where vanilla's depth is the sky's (overworld, not underwater: RR input,
     reference path, rr_resolve); vanilla's clouds still draw on top. SKY gain 2: at 1 (physical) the sky read
     dark navy next to sunlit snow. The sun's square halo is gone (the atmosphere's Mie glow and bloom make it).
     Cost about 0.35 ms (4.87 -> 5.2 ms path trace, LUT passes included).
   - User check fixes: silhouettes against the sky had a faint fringe of vanilla's sky (RR's input sampled
     vanilla's picture bilinearly at the jittered position): `vanillaAt` now swaps taps on vanilla's sky for
     Helio's. Night sky was far too bright: the moon lights the sky at 10% (Sky.MOON_SKY; its light on the
     ground stays); stars 1.2% of cells, brighter, faded by vanilla's star brightness. The moon is a 16x16 grey
     pixel face with vanilla's 8 phases (unlit part 3%), not a second sun (MOON_GLINT 2.5). Rain left sun and
     sky pitch black (rain brightness squared): now 2% of their light stays (RAIN_LIGHT), about a clear night's
     darkness, and the sun and moon discs fade out behind it.
   - Night sky upgrade (user: a low-light-pollution sky): `nightSky()` in pathtrace.comp. Two jittered star
     layers (cells at 350 and 220 per radian, about a pixel wide; magnitudes pow(u, 6); mostly white, some
     blue-white, yellow, orange), denser along the Milky Way: a band (16-degree sigma) with a warm brighter
     core, clumps and dark dust lanes from the cloud noise volume, peak 0.035. Turns with the sky; fades with
     vanilla's star brightness and under a bright moon. The moon's light and the sky's moon glow follow its
     phase (15% at new moon).
   - Follow-up (user): the Milky Way's core no longer dominates (0.6 + 1.2 x core), so the band reads all the
     way round, and it fades slowly through twilight (sun 2 to 20 degrees below the horizon) rather than with
     vanilla's star brightness. Stars twinkle at their own rates (1.5-4 Hz on game time), up to 60% near the
     horizon, 15% overhead. Night Brightness slider (default 2%): the share of the Brightness floor left outdoors
     at night (caves keep Cave Brightness at any hour).
4. [x] Volumetric clouds in place of vanilla's: ray-marched layer over the sky, lit by the sun through the
   LUTs, casting shadows on the ground.
   - Done in code: `cloud_noise.comp` makes a 128^3 tiling volume once (r Perlin-Worley shapes, stretched from
     their 0.5-0.75 bunch to 0..1; g Worley detail). `pathtrace.comp` `clouds()`: a layer from vanilla's cloud
     height up 90 blocks, weather pattern (12000-block tile) x shape (1500) eroded by detail (250, nearer than
     2000 blocks), 20 jittered steps with 3 towards the sun or moon (two HG lobes, a softer second octave, sky
     ambient), fading into the sky past ~3000 blocks. Over sky pixels, in front of traced hits the camera ray
     reaches through the layer (flying above them), and in reflections that escape. Sun and moon light get
     `cloudShadow` (two samples through the layer). Drift like vanilla's at first (see below); cover 45%, up to
     85% in rain; vanilla's Clouds Off turns them off; `CloudRendererMixin` skips vanilla's clouds while Helio
     draws the sky. Cost about 0.7 ms at the snowy lake (9.6 -> 10.3 ms): 48 steps were 0.5 ms more for little.
   - Shaders.compile now hands shaderc the source from the heap: the bigger shaders overflowed LWJGL's
     MemoryStack (the path tracer failed to start).
   - User tweaks: wind 0.1 blocks a tick (vanilla's 0.03 looked still on clouds this size), the detail drifting
     faster and rising so edges churn; layer 250 blocks thick; cumulus profile (flat base, cover shrinking to 45%
     at the top so towers narrow, tops fading out); detail inverted at the base (wisps) and billowing above,
     eroding by remap; light march 12/24/48/96 blocks; three scattering octaves; powder darkening on edges
     facing the light (none when looking towards it); ambient 35% at the base. 24 steps. Cost still within
     noise of no clouds at that spot (about 8.7 vs 8.2-9.4 ms).
5. [ ] Volumetric fog and god rays: sun shafts through trees and cave mouths, from one shadow ray per step
   of a short march; underwater fog the same way.
   - Done in code (air only): `pathtrace.comp` `fog()`: haze 0.0012 per block at sea level (0.0025 greyed the
     sky), thinning by e every 80 blocks up, 4x in rain; 4 jittered steps over the first 160 blocks, each a
     shadow ray to the sun or moon (cutout-tested, so leaves cut shafts) times `cloudShadow`, HG 0.6 phase, sky
     fill by the primary hit's sky light. Over traced hits and sky pixels, after the clouds. Shafts show through
     spruce next to the sun. Cost about 1 ms (6 steps: 1.9 ms). Underwater fog left for step 6 (water).
   - Render distance edge (user): vanilla faded its last chunks into its pale fog colour, a bright rim against
     Helio's below-horizon sky (dark navy). `FogRendererMixin` turns vanilla's distance and weather fog off
     while Helio draws the sky (not in water, lava, powder snow, blindness, darkness); `edgeFade` in
     pathtrace.comp fades vanilla's terrain into Helio's sky over the last 20% of the render distance; below
     the horizon the sky continues the horizon's haze, darkening to 45% about 20 degrees down.
6. [x] Water: waves (normal from a procedural height or the pack's water normal map), caustics on what's
   under it, underwater look.
   - Done in code: `waveHeight` / `waveNormal` in pathtrace.comp: three octaves of the cloud noise's smooth
     Worley channel sliced through game time (churns rather than slides), wide and flat (user: first try at twice
     the frequencies and slope 0.25 read too intense), slope 0.15; water's top faces reflect
     and refract about it (reflections pushed back above the face, refraction falling back to the flat face if
     a wave would turn it back). Caustics: a shadow ray rising through water is scaled by how a patch of the
     surface bunches or spreads the refracted light at its depth (inverse Jacobian of where it lands, clamped
     0.3-4). Underwater the haze is light shafts from the surface (4 steps over 48 blocks, through the waves,
     dimmed by the water to the eye). Cost about 0.6 ms at the lake (9.3 -> 9.9). Underwater not checked yet.
7. [x] Weather: rain wets surfaces (darker, smoother, by porosity) and fills puddles on flat ground.
   - Done in code: `PathTracer.updateWetness`: rain level where the camera is if its biome rains (none where it
     snows or never rains), soaking in over ~20 s, drying over ~90 s. `shade()` in pathtrace.comp: surfaces with
     near-full sky light get wet, up-facing most (walls 35%); albedo darkens by porosity (LabPBR _s blue 0-64,
     40% without data, 0 for metal) to 55%, roughness towards 0.12, F0 at least 0.02 (then specular below the
     cutoff); flat ground grows puddles from a low-frequency pattern (40- and 9-block scales) as wetness rises:
     roughness 0.02, flat normal, 70% albedo. Foliage stays dry. Only checked with the biome test bypassed
     (snowy test world); needs a look in a rainy biome.
8. [ ] Grading: colour-grading LUT, optional film grain and vignette.
9. [ ] Exit: side-by-side screenshots (vanilla / Helio) of the M3 scenes, at recorded coordinates.

### M7 — Performance pass

- Profile with Nsight Graphics (Linux) at the M3 scenes.
- Shader invocation reordering if exposed on this driver, half-res GI where
  RR hides it, ray budgets per preset.
- VRAM budget for 16 GB (BLAS pool, atlas sizes), RAM budget for 16 GB host. Scene
  memory has no cap yet: 3D Vanilla took it from 0.95 to 6.2 GB (VRAM 13.2 / 14.9 GB,
  M5 step 7). Shrink the RT distance when the scene nears its share of VRAM.
- CPU: mesh → BLAS input off the render thread; minimise FFM calls per frame.
- Specular reflection rays (M5 step 3) cost ~2.5 ms path trace in the water + glass
  scene with SPBR (11.7 -> 14.1 ms). Levers: a lower `SPECULAR_CUTOFF` (0.6), rough
  reflections from the radiance cache instead of a ray, reflection rays every other
  frame or at half resolution where RR hides it.
- Volumetric fog (M6 step 5) costs about 1 ms: 4 shadow rays per pixel. Levers: half-resolution or
  checkerboarded fog, fewer steps in open air, reusing the sun shadow of the primary hit for the last step.
- Distance-scaled ray budget: fewer rays for far terrain (e.g. bounce, block light
  and glass/water secondary rays skipped or traced every other frame past a
  distance, with the radiance cache standing in), since far pixels are small and
  RR smooths them anyway.

Exit: meet targets per preset and no regressions vs M4 numbers.

### M8 — Optimisation-mod compatibility

Test one mod at a time against the M3 scenes, then together. Start here,
checking each has a 26.3 Fabric build on Modrinth:

| Mod | What it does | Watch for |
|---|---|---|
| Lithium | game logic / tick | should be transparent |
| FerriteCore | memory | should be transparent; helps the 16 GB RAM |
| ModernFix | startup + memory | should be transparent |
| C2ME | parallel chunk loading | faster chunk arrival = more BLAS churn |
| Sodium | terrain renderer | replaces vanilla chunk meshes; needs a mesh adapter in `world/` |
| ImmediatelyFast | immediate-mode rendering | touches the same Vulkan paths as us |
| EntityCulling / More Culling | culling | can remove geometry RT needs (shadows/reflections) |
| Voxy | LODs (own GPU-driven renderer) | stretch: depth-composite its LOD pass first, then LOD sections as low-detail TLAS instances so far terrain casts shadows / shows in reflections |

With Voxy, LOD terrain past a distance threshold (configurable, default around
the RT distance) stays raster: Voxy draws it, Helio composites over it by depth
and spends no rays on it. Only LODs inside the threshold, if any, join the TLAS.

Iris shader packs are out of scope: Helio is the shader.

Exit: compat table in README; crashes fixed or mod marked incompatible.

### M9 — Release

- Modrinth page, Prism-friendly install (drop jar + Fabric API, done).
- First-run wizard: pick preset, auto-download DLSS, show self-check result.
- Advanced settings screen (user request, 2026-11-14): fine-tune anything a user may want, with today's values
  as the defaults and a reset per value and for all: sky gain, sun / moon / star brightness, cloud cover,
  thickness, wind and density, fog density, bloom, exposure key and range, water absorption and tint, foliage
  transmission, specular cutoff, normal map strength, rain darkness, and the like. The shader constants behind
  them move into the frame data so they change live.
- Recommended JVM args / Prism settings documented.
- CI build on tag.

### Stretch

- Photo mode: freeze camera, accumulate thousands of samples for
  offline-quality screenshots.
- Auto-PBR for vanilla textures (generated normals/roughness).
- Voxy integration (see M8). Voxy is all-rights-reserved: read its source
  only to understand its API/data layout for interop, never copy code from it.
  Integrate through its public hooks, or ask its author for an API if none fit.
- Port to 26.4 when released.

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| DLSS-G without Streamline on Linux Vulkan is undocumented | No MFG | **Retired in M0**: works, Helio presents generated frames itself |
| Frame pacing with FG under FIFO is ours to own | Judder / repeated frames | M4: pace from present timing (`VK_KHR_present_wait`), test VRR on KWin |
| Vanilla Vulkan backend is experimental and changes per version | Mixins break on updates | Small hook surface, one mixin per hook point, pin to one MC version at a time |
| Sodium replaces the meshes we read | Breaks with the most popular mod | Mesh adapter in M8; vanilla path works without it |
| Mod pack geometry (3D packs) blows up BLAS memory | VRAM spikes | Compaction, budgets, OMM |
| 16 GB host RAM | Stutter / OOM with big packs | Bounded staging, FerriteCore/ModernFix recommended |

## Prior art

- Radiance — own C++ Vulkan renderer, 1.20.1 / 1.21.1 / 1.21.4, 26.x port in progress.
- Radiante — Radiance fork on 26.3's vanilla Vulkan renderer; Linux
  experimental, DLSS Windows-only.
- Vitrail + iris-dlss — Iris shader packs on the vanilla Vulkan renderer with DLSS.
