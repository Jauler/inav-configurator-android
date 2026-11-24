'use strict';

import * as THREE from 'three';
import { isAndroid } from './platform';

/*
 * Workarounds for old mobile GPU drivers (verified on Adreno 305 / OpenGL ES 3.0,
 * Galaxy Tab 4), where the 3D craft models rendered completely black:
 *
 *  1. Lighting functions that accumulate into an `inout ReflectedLight` struct
 *     parameter leave it untouched, so all light contributions are zero.
 *  2. Copying a light out of the uniform array into a local struct
 *     (`directionalLight = directionalLights[ i ]`) yields zeros.
 *
 * On Android we therefore
 *  - rewrite the affected three.js shader chunks so they compute exactly the same
 *    thing without struct parameters/copies, and
 *  - render the glTF PBR materials with MeshLambertMaterial, whose shader we can
 *    patch this way (the PBR shader relies on the same pattern throughout).
 *
 * Desktop rendering is left unchanged.
 */

function replaceOnce(source, search, replacement, label) {
    const result = source.replace(search, replacement);
    if (result === source) {
        console.warn(`[3D] Shader chunk patch "${label}" did not apply; three.js changed?`);
    }
    return result;
}

function patchShaderChunks() {
    const chunks = THREE.ShaderChunk;

    // (2) Read the directional light straight from the uniform array.
    chunks.lights_fragment_begin = replaceOnce(
        chunks.lights_fragment_begin,
        'getDirectionalLightInfo( directionalLight, directLight );',
        'getDirectionalLightInfo( directionalLights[ i ], directLight );',
        'lights_fragment_begin'
    );

    // (1) No `out IncidentLight` struct parameter.
    chunks.lights_pars_begin = replaceOnce(
        chunks.lights_pars_begin,
        /void getDirectionalLightInfo\([^)]*\)\s*\{[\s\S]*?\n\t\}\n/,
        '#define getDirectionalLightInfo( directionalLight, light ) ' +
            'light.color = directionalLight.color; ' +
            'light.direction = directionalLight.direction; ' +
            'light.visible = true\n',
        'lights_pars_begin'
    );

    // (1) No `inout ReflectedLight` struct parameter.
    chunks.lights_lambert_pars_fragment = /* glsl */`
varying vec3 vViewPosition;

struct LambertMaterial {
	vec3 diffuseColor;
	float specularStrength;
};

#define RE_Direct( directLight, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight ) \\
	reflectedLight.directDiffuse += saturate( dot( geometryNormal, directLight.direction ) ) * directLight.color * BRDF_Lambert( material.diffuseColor )

#define RE_IndirectDiffuse( irradiance, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight ) \\
	reflectedLight.indirectDiffuse += ( irradiance ) * BRDF_Lambert( material.diffuseColor )
`;
}

if (isAndroid) {
    patchShaderChunks();
}

function toLambert(material) {
    if (!material || !material.isMeshStandardMaterial) {
        return material;
    }

    const lambert = new THREE.MeshLambertMaterial({
        name: material.name,
        color: material.color,
        map: material.map,
        emissive: material.emissive,
        emissiveMap: material.emissiveMap,
        emissiveIntensity: material.emissiveIntensity,
        vertexColors: material.vertexColors,
        transparent: material.transparent,
        opacity: material.opacity,
        alphaTest: material.alphaTest,
        side: material.side,
    });
    material.dispose();
    return lambert;
}

/** Call on every loaded glTF scene before it is rendered. No-op on desktop. */
export function adaptModelMaterials(root) {
    if (!isAndroid || !root) {
        return;
    }

    root.traverse((object) => {
        if (!object.isMesh) {
            return;
        }
        object.material = Array.isArray(object.material)
            ? object.material.map(toLambert)
            : toLambert(object.material);
    });
}
