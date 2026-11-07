package com.retro.engine.physics;

import com.retro.engine.ecs.Entity;

import java.util.List;

/**
 * Zero-Allocation Dynamic AABB Tree / BVH Broadphase Spatial Solver.
 * Manages spatial partitioning, fat AABB bounds padding, SAH node insertion, AVL balancing,
 * broadphase overlap candidate pair generation, and hierarchical ray-AABB branch pruning.
 */
public class BroadphaseSolver {

    public static final float FAT_AABB_MARGIN = 0.2f; // 20cm margin padding

    public static class Node {
        public int id;
        public PhysicsSystem.PhysicsBody body;

        public float minX, minY, minZ;
        public float maxX, maxY, maxZ;

        public int parent = -1;
        public int left = -1;
        public int right = -1;
        public int height = 0;

        public boolean isLeaf() {
            return left == -1;
        }
    }

    private Node[] nodes = new Node[128];
    private int rootId = -1;
    private int freeListId = 0;
    private int nodeCount = 0;

    // Stack buffers for zero-allocation tree traversal
    private final int[] queryStack = new int[1024];
    private final int[] pairStack = new int[1024];
    private final int[] raycastStack = new int[1024];
    private final ShapeRaycast shapeRaycast = new ShapeRaycast();

    public BroadphaseSolver() {
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = new Node();
            nodes[i].id = i;
            nodes[i].parent = i + 1;
        }
        nodes[nodes.length - 1].parent = -1;
    }

    private int allocateNode() {
        if (freeListId == -1) {
            int oldCap = nodes.length;
            Node[] newNodes = new Node[oldCap * 2];
            System.arraycopy(nodes, 0, newNodes, 0, oldCap);
            for (int i = oldCap; i < newNodes.length; i++) {
                newNodes[i] = new Node();
                newNodes[i].id = i;
                newNodes[i].parent = i + 1;
            }
            newNodes[newNodes.length - 1].parent = -1;
            freeListId = oldCap;
            nodes = newNodes;
        }

        int nodeId = freeListId;
        freeListId = nodes[nodeId].parent;

        Node node = nodes[nodeId];
        node.parent = -1;
        node.left = -1;
        node.right = -1;
        node.height = 1;
        node.body = null;
        nodeCount++;

        return nodeId;
    }

    private void freeNode(int nodeId) {
        nodes[nodeId].parent = freeListId;
        nodes[nodeId].body = null;
        freeListId = nodeId;
        nodeCount--;
    }

    public int insertLeaf(PhysicsSystem.PhysicsBody body, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int leafId = allocateNode();
        Node leaf = nodes[leafId];
        leaf.body = body;

        // Fat AABB padding
        leaf.minX = minX - FAT_AABB_MARGIN;
        leaf.minY = minY - FAT_AABB_MARGIN;
        leaf.minZ = minZ - FAT_AABB_MARGIN;
        leaf.maxX = maxX + FAT_AABB_MARGIN;
        leaf.maxY = maxY + FAT_AABB_MARGIN;
        leaf.maxZ = maxZ + FAT_AABB_MARGIN;

        if (rootId == -1) {
            rootId = leafId;
            return leafId;
        }

        // SAH Sibling Selection
        int bestSibling = rootId;
        float leafArea = area(leaf.minX, leaf.minY, leaf.minZ, leaf.maxX, leaf.maxY, leaf.maxZ);

        int current = rootId;
        while (!nodes[current].isLeaf()) {
            Node currNode = nodes[current];

            float combinedMinX = Math.min(currNode.minX, leaf.minX);
            float combinedMinY = Math.min(currNode.minY, leaf.minY);
            float combinedMinZ = Math.min(currNode.minZ, leaf.minZ);
            float combinedMaxX = Math.max(currNode.maxX, leaf.maxX);
            float combinedMaxY = Math.max(currNode.maxY, leaf.maxY);
            float combinedMaxZ = Math.max(currNode.maxZ, leaf.maxZ);

            float combinedArea = area(combinedMinX, combinedMinY, combinedMinZ, combinedMaxX, combinedMaxY, combinedMaxZ);

            float cost = 2.0f * combinedArea;
            float inheritanceCost = 2.0f * (combinedArea - area(currNode.minX, currNode.minY, currNode.minZ, currNode.maxX, currNode.maxY, currNode.maxZ));

            Node leftNode = nodes[currNode.left];
            float costLeft = inheritanceCost + (leftNode.isLeaf() ?
                area(Math.min(leftNode.minX, leaf.minX), Math.min(leftNode.minY, leaf.minY), Math.min(leftNode.minZ, leaf.minZ),
                     Math.max(leftNode.maxX, leaf.maxX), Math.max(leftNode.maxY, leaf.maxY), Math.max(leftNode.maxZ, leaf.maxZ)) + leafArea :
                area(Math.min(leftNode.minX, leaf.minX), Math.min(leftNode.minY, leaf.minY), Math.min(leftNode.minZ, leaf.minZ),
                     Math.max(leftNode.maxX, leaf.maxX), Math.max(leftNode.maxY, leaf.maxY), Math.max(leftNode.maxZ, leaf.maxZ)) - area(leftNode.minX, leftNode.minY, leftNode.minZ, leftNode.maxX, leftNode.maxY, leftNode.maxZ));

            Node rightNode = nodes[currNode.right];
            float costRight = inheritanceCost + (rightNode.isLeaf() ?
                area(Math.min(rightNode.minX, leaf.minX), Math.min(rightNode.minY, leaf.minY), Math.min(rightNode.minZ, leaf.minZ),
                     Math.max(rightNode.maxX, leaf.maxX), Math.max(rightNode.maxY, leaf.maxY), Math.max(rightNode.maxZ, leaf.maxZ)) + leafArea :
                area(Math.min(rightNode.minX, leaf.minX), Math.min(rightNode.minY, leaf.minY), Math.min(rightNode.minZ, leaf.minZ),
                     Math.max(rightNode.maxX, leaf.maxX), Math.max(rightNode.maxY, leaf.maxY), Math.max(rightNode.maxZ, leaf.maxZ)) - area(rightNode.minX, rightNode.minY, rightNode.minZ, rightNode.maxX, rightNode.maxY, rightNode.maxZ));

            if (cost < costLeft && cost < costRight) break;

            current = (costLeft < costRight) ? currNode.left : currNode.right;
        }

        bestSibling = current;

        // Create new internal parent
        int oldParent = nodes[bestSibling].parent;
        int newParent = allocateNode();
        Node parentNode = nodes[newParent];
        parentNode.parent = oldParent;

        parentNode.minX = Math.min(nodes[bestSibling].minX, leaf.minX);
        parentNode.minY = Math.min(nodes[bestSibling].minY, leaf.minY);
        parentNode.minZ = Math.min(nodes[bestSibling].minZ, leaf.minZ);
        parentNode.maxX = Math.max(nodes[bestSibling].maxX, leaf.maxX);
        parentNode.maxY = Math.max(nodes[bestSibling].maxY, leaf.maxY);
        parentNode.maxZ = Math.max(nodes[bestSibling].maxZ, leaf.maxZ);
        parentNode.height = nodes[bestSibling].height + 1;

        if (oldParent != -1) {
            if (nodes[oldParent].left == bestSibling) {
                nodes[oldParent].left = newParent;
            } else {
                nodes[oldParent].right = newParent;
            }
            parentNode.left = bestSibling;
            parentNode.right = leafId;
            nodes[bestSibling].parent = newParent;
            leaf.parent = newParent;
        } else {
            parentNode.left = bestSibling;
            parentNode.right = leafId;
            nodes[bestSibling].parent = newParent;
            leaf.parent = newParent;
            rootId = newParent;
        }

        // Walk back up to balance tree
        current = leaf.parent;
        while (current != -1) {
            current = balance(current);

            Node node = nodes[current];
            Node leftNode = nodes[node.left];
            Node rightNode = nodes[node.right];

            node.height = 1 + Math.max(leftNode.height, rightNode.height);
            node.minX = Math.min(leftNode.minX, rightNode.minX);
            node.minY = Math.min(leftNode.minY, rightNode.minY);
            node.minZ = Math.min(leftNode.minZ, rightNode.minZ);
            node.maxX = Math.max(leftNode.maxX, rightNode.maxX);
            node.maxY = Math.max(leftNode.maxY, rightNode.maxY);
            node.maxZ = Math.max(leftNode.maxZ, rightNode.maxZ);

            current = node.parent;
        }

        return leafId;
    }

    public void updateLeaf(int leafId, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (leafId < 0 || leafId >= nodes.length) return;
        Node leaf = nodes[leafId];

        // If body is still within fat AABB padding, avoid rebalancing
        if (minX >= leaf.minX && minY >= leaf.minY && minZ >= leaf.minZ &&
            maxX <= leaf.maxX && maxY <= leaf.maxY && maxZ <= leaf.maxZ) {
            return;
        }

        PhysicsSystem.PhysicsBody body = leaf.body;
        removeLeaf(leafId);
        // The free list is LIFO and removeLeaf frees the old parent last, so the re-inserted leaf usually gets a
        // different node id: keep the body's handle in sync or later updates / removals hit an internal node
        int newLeafId = insertLeaf(body, minX, minY, minZ, maxX, maxY, maxZ);
        if (body != null) body.treeNodeId = newLeafId;
    }

    public void removeLeaf(int leafId) {
        if (leafId < 0 || leafId >= nodes.length) return;
        if (leafId == rootId) {
            rootId = -1;
            freeNode(leafId);
            return;
        }

        int parentId = nodes[leafId].parent;
        int grandParentId = nodes[parentId].parent;
        int siblingId = (nodes[parentId].left == leafId) ? nodes[parentId].right : nodes[parentId].left;

        if (grandParentId != -1) {
            if (nodes[grandParentId].left == parentId) {
                nodes[grandParentId].left = siblingId;
            } else {
                nodes[grandParentId].right = siblingId;
            }
            nodes[siblingId].parent = grandParentId;
            freeNode(parentId);

            int current = grandParentId;
            while (current != -1) {
                current = balance(current);
                Node node = nodes[current];
                Node leftNode = nodes[node.left];
                Node rightNode = nodes[node.right];

                node.minX = Math.min(leftNode.minX, rightNode.minX);
                node.minY = Math.min(leftNode.minY, rightNode.minY);
                node.minZ = Math.min(leftNode.minZ, rightNode.minZ);
                node.maxX = Math.max(leftNode.maxX, rightNode.maxX);
                node.maxY = Math.max(leftNode.maxY, rightNode.maxY);
                node.maxZ = Math.max(leftNode.maxZ, rightNode.maxZ);
                node.height = 1 + Math.max(leftNode.height, rightNode.height);

                current = node.parent;
            }
        } else {
            rootId = siblingId;
            nodes[siblingId].parent = -1;
            freeNode(parentId);
        }

        freeNode(leafId);
    }

    private int balance(int i) {
        Node A = nodes[i];
        if (A.isLeaf() || A.height < 2) return i;

        int B = A.left;
        int C = A.right;
        Node nodeB = nodes[B];
        Node nodeC = nodes[C];

        int balance = nodeC.height - nodeB.height;

        if (balance > 1) {
            int F = nodeC.left;
            int G = nodeC.right;
            Node nodeF = nodes[F];
            Node nodeG = nodes[G];

            nodeC.left = i;
            nodeC.parent = A.parent;
            A.parent = C;

            if (nodeC.parent != -1) {
                if (nodes[nodeC.parent].left == i) {
                    nodes[nodeC.parent].left = C;
                } else {
                    nodes[nodeC.parent].right = C;
                }
            } else {
                rootId = C;
            }

            if (nodeF.height > nodeG.height) {
                nodeC.right = F;
                A.right = G;
                nodeG.parent = i;
                A.minX = Math.min(nodeB.minX, nodeG.minX);
                A.minY = Math.min(nodeB.minY, nodeG.minY);
                A.minZ = Math.min(nodeB.minZ, nodeG.minZ);
                A.maxX = Math.max(nodeB.maxX, nodeG.maxX);
                A.maxY = Math.max(nodeB.maxY, nodeG.maxY);
                A.maxZ = Math.max(nodeB.maxZ, nodeG.maxZ);

                nodeC.minX = Math.min(A.minX, nodeF.minX);
                nodeC.minY = Math.min(A.minY, nodeF.minY);
                nodeC.minZ = Math.min(A.minZ, nodeF.minZ);
                nodeC.maxX = Math.max(A.maxX, nodeF.maxX);
                nodeC.maxY = Math.max(A.maxY, nodeF.maxY);
                nodeC.maxZ = Math.max(A.maxZ, nodeF.maxZ);

                A.height = 1 + Math.max(nodeB.height, nodeG.height);
                nodeC.height = 1 + Math.max(A.height, nodeF.height);
            } else {
                nodeC.right = G;
                A.right = F;
                nodeF.parent = i;
                A.minX = Math.min(nodeB.minX, nodeF.minX);
                A.minY = Math.min(nodeB.minY, nodeF.minY);
                A.minZ = Math.min(nodeB.minZ, nodeF.minZ);
                A.maxX = Math.max(nodeB.maxX, nodeF.maxX);
                A.maxY = Math.max(nodeB.maxY, nodeF.maxY);
                A.maxZ = Math.max(nodeB.maxZ, nodeF.maxZ);

                nodeC.minX = Math.min(A.minX, nodeG.minX);
                nodeC.minY = Math.min(A.minY, nodeG.minY);
                nodeC.minZ = Math.min(A.minZ, nodeG.minZ);
                nodeC.maxX = Math.max(A.maxX, nodeG.maxX);
                nodeC.maxY = Math.max(A.maxY, nodeG.maxY);
                nodeC.maxZ = Math.max(A.maxZ, nodeG.maxZ);

                A.height = 1 + Math.max(nodeB.height, nodeF.height);
                nodeC.height = 1 + Math.max(A.height, nodeG.height);
            }

            return C;
        }

        if (balance < -1) {
            int D = nodeB.left;
            int E = nodeB.right;
            Node nodeD = nodes[D];
            Node nodeE = nodes[E];

            nodeB.left = i;
            nodeB.parent = A.parent;
            A.parent = B;

            if (nodeB.parent != -1) {
                if (nodes[nodeB.parent].left == i) {
                    nodes[nodeB.parent].left = B;
                } else {
                    nodes[nodeB.parent].right = B;
                }
            } else {
                rootId = B;
            }

            if (nodeD.height > nodeE.height) {
                nodeB.right = D;
                A.left = E;
                nodeE.parent = i;
                A.minX = Math.min(nodeC.minX, nodeE.minX);
                A.minY = Math.min(nodeC.minY, nodeE.minY);
                A.minZ = Math.min(nodeC.minZ, nodeE.minZ);
                A.maxX = Math.max(nodeC.maxX, nodeE.maxX);
                A.maxY = Math.max(nodeC.maxY, nodeE.maxY);
                A.maxZ = Math.max(nodeC.maxZ, nodeE.maxZ);

                nodeB.minX = Math.min(A.minX, nodeD.minX);
                nodeB.minY = Math.min(A.minY, nodeD.minY);
                nodeB.minZ = Math.min(A.minZ, nodeD.minZ);
                nodeB.maxX = Math.max(A.maxX, nodeD.maxX);
                nodeB.maxY = Math.max(A.maxY, nodeD.maxY);
                nodeB.maxZ = Math.max(A.maxZ, nodeD.maxZ);

                A.height = 1 + Math.max(nodeC.height, nodeE.height);
                nodeB.height = 1 + Math.max(A.height, nodeD.height);
            } else {
                nodeB.right = E;
                A.left = D;
                nodeD.parent = i;
                A.minX = Math.min(nodeC.minX, nodeD.minX);
                A.minY = Math.min(nodeC.minY, nodeD.minY);
                A.minZ = Math.min(nodeC.minZ, nodeD.minZ);
                A.maxX = Math.max(nodeC.maxX, nodeD.maxX);
                A.maxY = Math.max(nodeC.maxY, nodeD.maxY);
                A.maxZ = Math.max(nodeC.maxZ, nodeD.maxZ);

                nodeB.minX = Math.min(A.minX, nodeE.minX);
                nodeB.minY = Math.min(A.minY, nodeE.minY);
                nodeB.minZ = Math.min(A.minZ, nodeE.minZ);
                nodeB.maxX = Math.max(A.maxX, nodeE.maxX);
                nodeB.maxY = Math.max(A.maxY, nodeE.maxY);
                nodeB.maxZ = Math.max(A.maxZ, nodeE.maxZ);

                A.height = 1 + Math.max(nodeC.height, nodeD.height);
                nodeB.height = 1 + Math.max(A.height, nodeE.height);
            }

            return B;
        }

        return i;
    }

    public int generateOverlapPairs(List<PhysicsSystem.BodyPair> pairPool) {
        if (rootId == -1) return 0;

        int pairIndex = 0;
        int stackTop = 0;
        queryStack[stackTop++] = rootId;

        while (stackTop > 0) {
            int currId = queryStack[--stackTop];
            Node currNode = nodes[currId];

            if (!currNode.isLeaf()) {
                queryStack[stackTop++] = currNode.left;
                queryStack[stackTop++] = currNode.right;

                pairIndex = queryPairOverlap(currNode.left, currNode.right, pairPool, pairIndex);
            }
        }

        return pairIndex;
    }

    private int queryPairOverlap(int idA, int idB, List<PhysicsSystem.BodyPair> pairPool, int pairIndex) {
        int localStackTop = 0;
        pairStack[localStackTop++] = idA;
        pairStack[localStackTop++] = idB;

        while (localStackTop > 0) {
            int b = pairStack[--localStackTop];
            int a = pairStack[--localStackTop];

            Node nodeA = nodes[a];
            Node nodeB = nodes[b];

            if (nodeA.minX <= nodeB.maxX && nodeA.maxX >= nodeB.minX &&
                nodeA.minY <= nodeB.maxY && nodeA.maxY >= nodeB.minY &&
                nodeA.minZ <= nodeB.maxZ && nodeA.maxZ >= nodeB.minZ) {

                if (nodeA.isLeaf() && nodeB.isLeaf()) {
                    if (nodeA.body != nodeB.body) {
                        while (pairPool.size() <= pairIndex) {
                            pairPool.add(new PhysicsSystem.BodyPair());
                        }
                        PhysicsSystem.BodyPair pair = pairPool.get(pairIndex++);
                        pair.bodyA = nodeA.body;
                        pair.bodyB = nodeB.body;
                    }
                } else if (nodeA.isLeaf()) {
                    pairStack[localStackTop++] = a;
                    pairStack[localStackTop++] = nodeB.left;
                    pairStack[localStackTop++] = a;
                    pairStack[localStackTop++] = nodeB.right;
                } else if (nodeB.isLeaf()) {
                    pairStack[localStackTop++] = nodeA.left;
                    pairStack[localStackTop++] = b;
                    pairStack[localStackTop++] = nodeA.right;
                    pairStack[localStackTop++] = b;
                } else {
                    pairStack[localStackTop++] = nodeA.left;
                    pairStack[localStackTop++] = nodeB.left;
                    pairStack[localStackTop++] = nodeA.left;
                    pairStack[localStackTop++] = nodeB.right;
                    pairStack[localStackTop++] = nodeA.right;
                    pairStack[localStackTop++] = nodeB.left;
                    pairStack[localStackTop++] = nodeA.right;
                    pairStack[localStackTop++] = nodeB.right;
                }
            }
        }

        return pairIndex;
    }

    public void raycastInPlace(Entity excludeEntity, float rayOriginX, float rayOriginY, float rayOriginZ,
                               float rayDirX, float rayDirY, float rayDirZ, float maxDistance, RaycastHit outHit, int layerMask) {
        float dirLen = (float) Math.sqrt(rayDirX * rayDirX + rayDirY * rayDirY + rayDirZ * rayDirZ);
        if (dirLen <= 0.00001f) {
            outHit.hit = false;
            outHit.distance = 0.0f;
            outHit.entity = null;
            return;
        }
        cast(excludeEntity, null, rayOriginX, rayOriginY, rayOriginZ, rayDirX / dirLen, rayDirY / dirLen, rayDirZ / dirLen,
                maxDistance, 0.0f, false, layerMask, outHit);
    }

    /**
     * Closest solid shape along a unit ray. {@code inflate} makes it a sphere sweep of that radius, and
     * {@code immovableOnly} skips dynamic bodies. Only layers in {@code layerMask} count. Triggers aren't solid, so rays pass through them, including every
     * character's ground probe.
     */
    void cast(Entity excludeEntity, Entity alsoExclude, float ox, float oy, float oz, float dx, float dy, float dz, float maxDistance,
              float inflate, boolean immovableOnly, int layerMask, RaycastHit outHit) {
        outHit.hit = false;
        outHit.distance = 0.0f;
        outHit.entity = null;
        if (rootId == -1) return;

        float reach = maxDistance;
        float ix = 1.0f / (dx != 0 ? dx : 1e-8f), iy = 1.0f / (dy != 0 ? dy : 1e-8f), iz = 1.0f / (dz != 0 ? dz : 1e-8f);
        int stackTop = 0;
        raycastStack[stackTop++] = rootId;

        while (stackTop > 0) {
            Node node = nodes[raycastStack[--stackTop]];
            float t1 = (node.minX - inflate - ox) * ix, t2 = (node.maxX + inflate - ox) * ix;
            float t3 = (node.minY - inflate - oy) * iy, t4 = (node.maxY + inflate - oy) * iy;
            float t5 = (node.minZ - inflate - oz) * iz, t6 = (node.maxZ + inflate - oz) * iz;
            float tmin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
            float tmax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));

            // Node bounds only gate traversal: a ray starting inside a node (tmin < 0) can still hit its children
            if (tmax < Math.max(tmin, 0.0f) || tmin > reach) continue;
            if (!node.isLeaf()) {
                raycastStack[stackTop++] = node.left;
                raycastStack[stackTop++] = node.right;
                continue;
            }

            PhysicsSystem.PhysicsBody body = node.body;
            if (body == null || body.isTrigger() || body.entity == excludeEntity || body.entity == alsoExclude) continue;
            if (immovableOnly && body.invMass > 0.0f) continue;
            if ((body.layerBit & layerMask) == 0) continue;
            float t = shapeRaycast.cast(body, ox, oy, oz, dx, dy, dz, reach, inflate);
            if (t < 0.0f) continue;

            reach = t; // Anything further away can't win, so stop looking past it
            outHit.hit = true;
            outHit.entity = body.entity;
            outHit.distance = t;
            outHit.pointX = ox + dx * t;
            outHit.pointY = oy + dy * t;
            outHit.pointZ = oz + dz * t;
            outHit.normalX = shapeRaycast.normalX;
            outHit.normalY = shapeRaycast.normalY;
            outHit.normalZ = shapeRaycast.normalZ;
        }
    }

    private float area(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        return 2.0f * (dx * dy + dy * dz + dz * dx);
    }

    public void clear() {
        rootId = -1;
        nodeCount = 0;
        for (int i = 0; i < nodes.length; i++) {
            nodes[i].parent = i + 1;
            nodes[i].left = -1;
            nodes[i].right = -1;
            nodes[i].body = null;
        }
        nodes[nodes.length - 1].parent = -1;
        freeListId = 0;
    }
}
