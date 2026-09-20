package com.mozhi.reader.feature.reader

/**
 * Cylindrical paper in height-normalized coordinates. Material slices remain attached to
 * the binding while the outer edge can bend diagonally with the fingertip.
 */
internal const val MODERN_PAGE_CURL_SHADER = """
uniform shader image;
uniform shader backImage;
uniform shader underImage;
uniform float2 resolution;
uniform float4 backfaceOverlay;
uniform float4 iMouse;
uniform float radiusScale;
uniform float doublePageCurlRadius;
uniform float doublePageFoldPosition;
uniform float doublePageProgress;
uniform float doublePageDirection;
uniform float doublePageGrabMaterialX;
uniform float doublePageGrabV;
uniform float doublePageCurrentV;
uniform float doublePageMode;
uniform float2 leafGeometry;
const float PI = 3.14159265359;

bool inPaper(float2 p, float width) {
    return p.x >= 0.0 && p.x <= width && p.y >= 0.0 && p.y <= 1.0;
}
half4 overlayPaper(half4 ink, float amount) {
    ink.rgb = half3(mix(float3(ink.rgb), backfaceOverlay.rgb * float(ink.a), clamp(amount, 0.0, 1.0)));
    return ink;
}
float2 cylinder(float axis, float radius, float angle, float fold) {
    float delta = axis - fold;
    float theta = clamp(delta / radius, 0.0, angle);
    float tail = max(delta - radius * angle, 0.0);
    return float2(fold + radius * sin(theta) + min(delta, 0.0) + tail * cos(angle),
        radius * (1.0 - cos(theta)) + tail * sin(angle));
}
float4 surfaceCandidate(float axis, float valid, float2 tangentPart, float2 normal,
    float radius, float angle, float baseline, float width, float fold) {
    float2 material = tangentPart + normal * axis;
    if (valid < 0.5 || !inPaper(material, width)) return float4(0.0, 0.0, -1000000.0, 1.0);
    float theta = clamp((axis - fold) / radius, 0.0, angle);
    return float4(material, cylinder(axis, radius, angle, fold).y - baseline, cos(theta));
}
float4 boundSurface(float2 uv, float2 normal, float radius, float angle, float width, float fold) {
    float2 tangent = float2(-normal.y, normal.x);
    float v = dot(uv, tangent);
    float spineAxis = normal.y * v / normal.x;
    float2 spine = cylinder(spineAxis, radius, angle, fold);
    // Subtract the spine's displacement and depth for every slice: the binding stays put.
    float projected = dot(uv, normal) + spine.x - spineAxis;
    float2 tangentPart = tangent * v;
    float cs = cos(angle);
    float arcEnd = fold + radius * angle;
    float tailAxis = arcEnd + (projected - fold - radius * sin(angle))
        / ((cs >= 0.0 ? 1.0 : -1.0) * max(abs(cs), 0.00001));
    float ratio = (projected - fold) / radius;
    float theta = asin(clamp(ratio, 0.0, 1.0));
    float arcValid = step(0.0, ratio) * step(ratio, 1.0);
    // Four inverse solutions can overlap. Only the nearest valid paper surface is visible.
    float4 best = surfaceCandidate(projected, step(projected, fold + 0.00001),
        tangentPart, normal, radius, angle, spine.y, width, fold);
    float4 candidate = surfaceCandidate(tailAxis, step(0.00001, abs(cs)) * step(arcEnd - 0.00001, tailAxis),
        tangentPart, normal, radius, angle, spine.y, width, fold);
    if (candidate.z > best.z + 0.000001) best = candidate;
    candidate = surfaceCandidate(fold + radius * theta, arcValid * step(theta, angle + 0.00001),
        tangentPart, normal, radius, angle, spine.y, width, fold);
    if (candidate.z > best.z + 0.000001) best = candidate;
    candidate = surfaceCandidate(fold + radius * (PI - theta), arcValid * step(PI - theta, angle + 0.00001),
        tangentPart, normal, radius, angle, spine.y, width, fold);
    if (candidate.z > best.z + 0.000001) best = candidate;
    return best;
}
half4 shadePaper(half4 ink, float cosTheta, float overlay) {
    ink = overlayPaper(ink, overlay);
    ink.rgb = half3(clamp(float3(ink.rgb) * mix(0.8, 1.0, sqrt(cosTheta))
        + pow(1.0 - cosTheta, 8.0) * 0.07 * float(ink.a), 0.0, float(ink.a)));
    float thickness = mix(0.94, 1.0, sqrt(sqrt(cosTheta)));
    ink.rgb *= half(thickness);
    return ink;
}
float4 singleSurface(float2 uv, float2 normal, float radius, float width, float fold) {
    float2 tangent = float2(-normal.y, normal.x);
    float v = dot(uv, tangent);
    float projected = dot(uv, normal);
    float spineAxis = normal.y * v / normal.x;
    if (spineAxis > fold) projected += cylinder(spineAxis, radius, PI, fold).x - spineAxis;
    float t = projected - fold;
    if (t > radius) return float4(0.0, 0.0, -1000000.0, 1.0);
    float2 base = tangent * v;
    if (t >= 0.0) {
        float ratio = clamp(t / radius, 0.0, 1.0);
        float theta = asin(ratio);
        float cosine = sqrt(max(1.0 - ratio * ratio, 0.0));
        float2 back = base + normal * (fold + radius * (PI - theta));
        if (inPaper(back, width)) return float4(back, 0.0, -cosine);
        float2 front = base + normal * (fold + radius * theta);
        if (inPaper(front, width)) return float4(front, 0.0, cosine);
    } else {
        float2 back = base + normal * (fold + PI * radius - t);
        if (inPaper(back, width)) return float4(back, 0.0, -1.0);
        float2 front = spineAxis <= fold ? uv : base + normal * projected;
        if (inPaper(front, width)) return float4(front, 0.0, 1.0);
    }
    return float4(0.0, 0.0, -1000000.0, 1.0);
}
half4 singlePage(float2 xy) {
    float width = resolution.x / resolution.y;
    float2 uv = xy / resolution.y;
    float2 finger = iMouse.xy / resolution.y;
    float p = clamp((width - finger.x) / (2.0 * width), 0.0, 1.0);
    float anchor = clamp(iMouse.w / resolution.y, 0.0, 1.0);
    if (anchor < 1.0 / 3.0) anchor *= smoothstep(0.0, 1.0, anchor * 3.0);
    else if (anchor > 2.0 / 3.0) anchor = 1.0 - (1.0 - anchor) * smoothstep(0.0, 1.0, (1.0 - anchor) * 3.0);
    float2 grab = float2(width, anchor);
    float dx = max(width - finger.x, 0.00001);
    float rawSlope = (finger.y - anchor) / max(dx, width * 0.18);
    // The reflected outer corner must not pass the binding while its tip is still on this
    // side of the book. Solve sin(2*angle) from the available reach, rather than using a
    // fixed narrow tilt limit. Corner grabs can bend farther than middle-of-page grabs.
    float lever = rawSlope >= 0.0 ? anchor : 1.0 - anchor;
    float reach = max(finger.x - width * 0.055, 0.0);
    float sineLimit = clamp(reach / max(lever, 0.00001), 0.0, 1.0);
    float maxSlope = sineLimit / (1.0 + sqrt(max(1.0 - sineLimit * sineLimit, 0.0)));
    float slope = sign(rawSlope) * min(abs(rawSlope), maxSlope);
    slope *= 1.0 - smoothstep(0.65, 1.0, p);
    float2 normal = normalize(float2(1.0, -slope));
    float2 tip = float2(finger.x, anchor + slope * dx);
    float dragged = distance(grab, tip);
    float radius = mix(0.065, 0.04, smoothstep(0.0, 0.2, dragged)) * radiusScale;
    // Advance the fold with the fingertip before forming a full semicylinder. This keeps
    // short drags responsive without borrowing the two-page hinge's slower progress curve.
    float fold = dot(tip, normal) + max((dragged - PI * radius) * 0.5, 0.0);
    fold -= PI * radius * 0.5 * smoothstep(0.8, 1.0, p);
    float4 surface = singleSurface(uv, normal, radius, width, fold);
    if (surface.z < -1000.0) return half4(0.0);
    float backside = surface.w < 0.0 ? 1.0 : 0.0;
    float cosTheta = clamp(abs(surface.w), 0.0, 1.0);
    half4 ink = backside > 0.5 ? backImage.eval(surface.xy * resolution.y) : image.eval(surface.xy * resolution.y);
    ink = shadePaper(ink, cosTheta, backfaceOverlay.a * backside * sqrt(cosTheta));
    return ink;
}

half4 leafInk(float2 material, float backside) {
    float u = clamp(material.x * resolution.y / leafGeometry.x, 0.0, 1.0);
    float leaf = leafGeometry.x;
    float right = leaf + leafGeometry.y;
    float sourceX;
    if (backside > 0.5) {
        sourceX = doublePageDirection > 0.0 ? (1.0 - u) * leaf : right + u * leaf;
        return backImage.eval(float2(sourceX, material.y * resolution.y));
    }
    sourceX = doublePageDirection > 0.0 ? right + u * leaf : (1.0 - u) * leaf;
    return image.eval(float2(sourceX, material.y * resolution.y));
}
half4 doublePage(float2 xy) {
    float p = clamp(doublePageProgress, 0.0, 1.0);
    float width = leafGeometry.x / resolution.y;
    float gap = leafGeometry.y * 0.5;
    float2 uv = float2((doublePageDirection * (xy.x - resolution.x * 0.5) - gap * (1.0 - 2.0 * p)) / resolution.y,
        xy.y / resolution.y);
    float grab = width * doublePageGrabMaterialX;
    float2 delta = float2(2.0 * grab * p, doublePageGrabV - doublePageCurrentV);
    if (length(delta) < 0.0000001) return inPaper(uv, width) ? leafInk(uv, 0.0) : half4(0.0);
    float2 normal = normalize(delta);
    if (normal.x <= 0.00001) return inPaper(uv, width) ? leafInk(uv, 0.0) : half4(0.0);
    float radius = max(doublePageCurlRadius, 0.00001);
    float angle = mix(acos(clamp(1.0 - 2.0 * p, -1.0, 1.0)), PI, smoothstep(0.0, 0.12, p));
    float4 surface = boundSurface(uv, normal, radius, angle, width, doublePageFoldPosition);
    if (surface.z < -1000.0) return half4(0.0);
    float backside = surface.w < 0.0 ? 1.0 : 0.0;
    float cosTheta = clamp(abs(surface.w), 0.0, 1.0);
    half4 ink = shadePaper(leafInk(surface.xy, backside), cosTheta, backfaceOverlay.a * backside * (1.0 - cosTheta));
    float binding = 0.15 * (1.0 - clamp(surface.x * resolution.y / max(leafGeometry.x * 0.028, 1.0), 0.0, 1.0)) * sin(PI * p);
    ink.rgb *= half(1.0 - binding);
    return ink;
}
half4 main(float2 xy) {
    half4 below = underImage.eval(xy);
    if (doublePageMode > 0.5 && doublePageDirection * (xy.x - resolution.x * 0.5) < -leafGeometry.y * 0.5)
        below = image.eval(xy);
    half4 paper = doublePageMode > 0.5 ? doublePage(xy) : singlePage(xy);
    return paper + below * (1.0 - paper.a);
}
"""
