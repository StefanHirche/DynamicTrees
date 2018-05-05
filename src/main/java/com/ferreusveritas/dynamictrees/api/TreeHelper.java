package com.ferreusveritas.dynamictrees.api;

import java.util.HashMap;

import com.ferreusveritas.dynamictrees.api.network.MapSignal;
import com.ferreusveritas.dynamictrees.api.treedata.ILeavesProperties;
import com.ferreusveritas.dynamictrees.api.treedata.ITreePart;
import com.ferreusveritas.dynamictrees.blocks.BlockBranch;
import com.ferreusveritas.dynamictrees.blocks.BlockDynamicLeaves;
import com.ferreusveritas.dynamictrees.blocks.BlockRooty;
import com.ferreusveritas.dynamictrees.blocks.NullTreePart;
import com.ferreusveritas.dynamictrees.systems.nodemappers.NodeTwinkle;
import com.ferreusveritas.dynamictrees.trees.Species;
import com.ferreusveritas.dynamictrees.util.SimpleVoxmap;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

public class TreeHelper {
	
	private static HashMap<String, HashMap<Integer, BlockDynamicLeaves> > modLeavesArray = new HashMap<String, HashMap<Integer, BlockDynamicLeaves>>();
	
	public static final ITreePart nullTreePart = new NullTreePart();
	
	
	public static BlockDynamicLeaves getLeavesBlockForSequence(String modid, int seq, ILeavesProperties leavesProperties) {
		BlockDynamicLeaves leaves = getLeavesBlockForSequence(modid, seq);
		int tree = seq & 3;
		leavesProperties.setDynamicLeavesState(leaves.getDefaultState().withProperty(BlockDynamicLeaves.TREE, tree));
		leaves.setProperties(tree, leavesProperties);
		return leaves;
	}
	
	/**
	 * A convenience function for packing 4 {@link BlockDynamicLeaves} blocks into one Minecraft block using metadata.
	 * 
	 * @param modid
	 * @param seq
	 * @return
	 */
	public static BlockDynamicLeaves getLeavesBlockForSequence(String modid, int seq) {
		int key = seq / 4;
		String regname = "leaves" + key;
		
		return getLeavesMapForModId(modid).computeIfAbsent(key, k -> (BlockDynamicLeaves)new BlockDynamicLeaves().setRegistryName(modid, regname).setUnlocalizedName(regname));
	}
	
	/**
	 * 	Get the map of leaves from for the appropriate modid.
	 *  If the map does not exist then one is created.
	 * 
	 * @param modid The ModId of the mod accessing this
	 * @return The map of {@link BlockDynamicLeaves}
	 */
	public static HashMap<Integer, BlockDynamicLeaves> getLeavesMapForModId(String modid) {
		return modLeavesArray.computeIfAbsent(modid, k -> new HashMap<Integer, BlockDynamicLeaves>());
	}
	
	/**
	 * Convenience method to pulse a single growth cycle and age the cuboid volume.
	 * Used by the growth potion and the dendrocoil.
	 * 
	 * @param world
	 * @param rootPos
	 */
	public static void growPulse(World world, BlockPos rootPos) {
		IBlockState rootyState = world.getBlockState(rootPos);
		BlockRooty dirt = TreeHelper.getRooty(rootyState);
		if(dirt != null) {
			dirt.updateTree(rootyState, world, rootPos, world.rand, true);
			ageVolume(world, rootPos, 8, 32, 1);//blindly age a cuboid volume
		}
	}
	
	/**
	 * Pulses an entire leafMap volume of blocks each with an age signal.
	 * Warning: CPU intensive and should be used sparingly
	 * 
	 * @param world The world
	 * @param iterMap The voxel map of hydrovalues to use as a iterator
	 * @param iterations The number of times to age the map
	 */
	public static void ageVolume(World world, SimpleVoxmap leafMap, int iterations){
		
		//The iterMap is the voxmap we will use as a discardable.  The leafMap must survive for snow
		SimpleVoxmap iterMap = leafMap != null ? new SimpleVoxmap(leafMap) : null;
		Iterable<MutableBlockPos> iterable = iterMap.getAllNonZero();
		
		for(int i = 0; i < iterations; i++) {
			for(MutableBlockPos iPos: iterable) {
				IBlockState blockState = world.getBlockState(iPos);
				Block block = blockState.getBlock();
				if(block instanceof BlockDynamicLeaves) {//Special case for leaves
					int prevHydro = leafMap.getVoxel(iPos);//The leafMap should contain accurate hydro data
					int newHydro = ((IAgeable)block).age(world, iPos, blockState, world.rand, true);//Get new values from neighbors
					if(newHydro == -1) {
						//Leaf block died.  Take it out of the leafMap and iterMap
						leafMap.setVoxel(iPos, (byte) 0);
						iterMap.setVoxel(iPos, (byte) 0);
					} else {
						//Leaf did not die so the block is still leaves
						if(prevHydro == newHydro) { //But it didn't change
							iterMap.setVoxel(iPos, (byte) 0); //Stop iterating over it if it's not changing
						} else {//Oh wait.. it did change
							//Update both maps with this new hydro value
							leafMap.setVoxel(iPos, (byte) newHydro);
							iterMap.setVoxel(iPos, (byte) newHydro);
							//Copy all the surrounding values from the leafMap to the iterMap since they now also have potential to change
							for(EnumFacing dir: EnumFacing.values()) {
								BlockPos dPos = iPos.offset(dir);
								iterMap.setVoxel(dPos, leafMap.getVoxel(dPos));
							}
						}
					}
				}
				else if(block instanceof IAgeable) {//Treat just a regular ageable block
					((IAgeable)block).age(world, iPos, blockState, world.rand, true);
				} else {//You're not supposed to be here
					leafMap.setVoxel(iPos, (byte) 0);
					iterMap.setVoxel(iPos, (byte) 0);
				}
			}
		}
		
	}
	
	/**
	 * Pulses an entire cuboid volume of blocks each with an age signal.
	 * Warning: CPU intensive and should be used sparingly
	 * 
	 * @param world The world
	 * @param treePos The position of the bottom most block of a trees trunk
	 * @param halfWidth The "radius" of the cuboid volume
	 * @param height The height of the cuboid volume
 	 * @param iterations The number of times to age the volume
	 */
	public static void ageVolume(World world, BlockPos treePos, int halfWidth, int height, int iterations){
		//Slow and dirty iteration over a cuboid volume.  Try to avoid this by using a voxmap if you can
		Iterable<MutableBlockPos> iterable = BlockPos.getAllInBoxMutable(treePos.add(new BlockPos(-halfWidth, 0, -halfWidth)), treePos.add(new BlockPos(halfWidth, height, halfWidth)));
		for(int i = 0; i < iterations; i++) {
			for(MutableBlockPos iPos: iterable) {
				IBlockState blockState = world.getBlockState(iPos);
				Block block = blockState.getBlock();
				if(block instanceof IAgeable) {
					((IAgeable)block).age(world, iPos, blockState, world.rand, true);//Treat just a regular ageable block
				}
			}
		}
		
	}

	
	/**
	 * This is resource intensive.  Use only for interaction code.
	 * Only the root node can determine the exact species and it has
	 * to be found by mapping the branch network.
	 * 
	 * @param world
	 * @param pos
	 * @return
	 */
	public static Species getExactSpecies(IBlockState blockState, World world, BlockPos pos) {
		BlockPos rootPos = findRootNode(blockState, world, pos);
		if(rootPos != BlockPos.ORIGIN) {
			IBlockState rootyState = world.getBlockState(rootPos);
			return TreeHelper.getRooty(rootyState).getSpecies(rootyState, world, rootPos);
		}
		return Species.NULLSPECIES;
	}
	
	/**
	 * Find the root node of a tree.
	 * 
	 * @param blockState The blockState of either a branch or root block in world at pos
	 * @param world The world
	 * @param pos The position being analyzed
	 * @return The position of the root node of the tree or BlockPos.ORIGIN if nothing was found.
	 */
	public static BlockPos findRootNode(IBlockState blockState, World world, BlockPos pos) {
		
		ITreePart treePart = TreeHelper.getTreePart(blockState);
		
		switch(treePart.getTreePartType()) {
			case BRANCH:
				MapSignal signal = treePart.analyse(blockState, world, pos, null, new MapSignal());// Analyze entire tree network to find root node
				if(signal.found) {
					return signal.root;
				}
				break;
			case ROOT:
				return pos;
			default:
				return BlockPos.ORIGIN;
		}
		
		return BlockPos.ORIGIN;
	}
	
	/**
	 * Convenience function that spawns particles all over the tree branches
	 * 
	 * @param world
	 * @param rootPos
	 * @param type
	 * @param num
	 */
	public static void treeParticles(World world, BlockPos rootPos, EnumParticleTypes type, int num) {
		if(world.isRemote) {
			startAnalysisFromRoot(world, rootPos, new MapSignal(new NodeTwinkle(type, num)));
		}
	}
	
	/**
	 * Convenience function that verifies an analysis is starting from the root
	 * node before commencing.
	 * 
	 * @param world The world
	 * @param rootPos The position of the rootyBlock
	 * @param signal The signal carrying the inspectors
	 * @return true if a root block was found.
	 */
	public static boolean startAnalysisFromRoot(World world, BlockPos rootPos, MapSignal signal) {
		BlockRooty dirt = TreeHelper.getRooty(world.getBlockState(rootPos));
		if(dirt != null) {
			dirt.startAnalysis(world, rootPos, signal);
			return true;
		}
		return false;
	}
	
	//Treeparts
	
	public final static boolean isTreePart(Block block) {
		return block instanceof ITreePart;
	}
	
	public final static boolean isTreePart(IBlockAccess blockAccess, BlockPos pos) {
		return isTreePart(blockAccess.getBlockState(pos).getBlock());
	}
	
	public final static ITreePart getTreePart(Block block) {
		return isTreePart(block)? (ITreePart)block : nullTreePart;
	}
	
	public final static ITreePart getTreePart(IBlockState blockState) {
		return getTreePart(blockState.getBlock());
	}
	
	
	//Branches
	
	public final static boolean isBranch(Block block) {
		return block instanceof BlockBranch;//Oh shuddap you java purists.. this is minecraft!
	}
	
	public final static boolean isBranch(IBlockState state) {
		return isBranch(state.getBlock());
	}
	
	public final static BlockBranch getBranch(Block block) {
		return isBranch(block) ? (BlockBranch)block : null;
	}
	
	public final static BlockBranch getBranch(ITreePart treepart) {
		return treepart instanceof BlockBranch ? (BlockBranch)treepart : null;
	}
	
	public final static BlockBranch getBranch(IBlockState state) {
		return getBranch(state.getBlock());
	}
	
	//Leaves
	
	public final static boolean isLeaves(Block block) {
		return block instanceof BlockDynamicLeaves;
	}
	
	public final static boolean isLeaves(IBlockState blockState) {
		return isLeaves(blockState.getBlock());
	}
	
	public final static BlockDynamicLeaves getLeaves(Block block) {
		return isLeaves(block) ? (BlockDynamicLeaves)block : null;
	}
	
	public final static BlockDynamicLeaves getLeaves(ITreePart treepart) {
		return treepart instanceof BlockDynamicLeaves ? (BlockDynamicLeaves)treepart : null;
	}
	
	public final static BlockDynamicLeaves getLeaves(IBlockState state) {
		return getLeaves(state.getBlock());
	}
	
	//Rooty
	
	public final static boolean isRooty(Block block) {
		return block instanceof BlockRooty;
	}
	
	public final static boolean isRooty(IBlockState blockState) {
		return isRooty(blockState.getBlock());
	}
	
	public final static BlockRooty getRooty(Block block) {
		return isRooty(block) ? (BlockRooty)block : null;
	}
	
	public final static BlockRooty getRooty(ITreePart treepart) {
		return treepart instanceof BlockRooty ? (BlockRooty)treepart : null;
	}
	
	public final static BlockRooty getRooty(IBlockState blockState) {
		return getRooty(blockState.getBlock());
	}
	
}
