#version 450

// Same little-endian RGBA packing as entity_id.frag, so the host reads the overlay ID directly.
layout(location = 0) flat in uint vPickId;

layout(location = 0) out uvec4 outPickId;

void main() {
    uint id = vPickId;
    outPickId = uvec4(id & 0xFFu, (id >> 8) & 0xFFu, (id >> 16) & 0xFFu, id >> 24);
}
