package player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import map.Milepost;
import map.MilepostId;
import train.GameException;

public class GlobalRail {
	private Map<String, Rail> rails; //maps pid to their track
	
	public GlobalRail(){
		rails = new HashMap<String, Rail>();
	}
	
	public void join(String pid){
		rails.put(pid, new Rail(pid));
	}
	
	/** returns -1 if the build is invalid
	 * Rules for building:
	 * 1. Mileposts must be contiguous
	 * 2. Mileposts must not be blank
	 * 3. Cannot build through a major city
	 * 4. Track must either start from a major city, or from own player's existing track
	 * 5. If continuousPlay is off, then no other player can have already connected the same mileposts
	 * */
	public int checkBuild(String pid, Milepost[] mps, boolean continuousPlay) throws GameException{
		int cost = 0;
		Milepost fst = mps[0];
		// Track must either start from a major city, or from own player's existing track
		if (!(contains(pid, fst.getMilepostId()) || fst.isMajorCity()))
			return -1;
		for(int i = 1; i < mps.length; i++){
			Milepost snd = mps[i];
			if(!fst.isNeighbor(snd.getMilepostId())) 
			{
				//log.debug("Mileposts are not contiguous ({}, {}) and ({}, {})", 
				//		mps[i].x, mps[i].y, mps[i + 1].x, mps[i + 1].y);
				return -1;
			}
			if (!continuousPlay && anyConnects(fst.getMilepostId(), snd.getMilepostId()))
			{
				//log.warn("Track is already built there ({}, {}) and ({}, {})",
				//		mps[i].x, mps[i].y, mps[i + 1].x, mps[i + 1].y);
				return -1;
			}
			if (fst.isSameCity(snd)) {
				//log.info("Cannot build through major city");
				return -1;
			}
			if (snd.type == Milepost.Type.BLANK) {
				// log.warn("Mileposts is blank ({}, {})", mps[i + 1].x, mps[i + 1].y);
				return -1;
			}
			cost += fst.getCost(snd.getMilepostId());
			fst = snd;
		}
		return cost;
	}
	
	public void build(String pid, Milepost[] mps) throws GameException{
		if(!rails.containsKey(pid))
			throw new GameException("PlayerNotFound");
		Rail r = rails.get(pid);
		Milepost fst = mps[0];
		for(int i = 1; i < mps.length; i++){
			Milepost snd = mps[i];
			r.build(fst, snd);
			fst = snd;
		}
	}
	
	public boolean testMove(String rid, MilepostId[] mps) throws GameException{
		if(!rails.containsKey(rid)) return false;
		MilepostId fst = mps[0];
		for(int i = 1; i < mps.length; i++){
			MilepostId snd = mps[i];
			if(!connects(rid, fst, snd)) return false;
			fst = snd;
		}
		return true;
	}
	
	public boolean contains(String pid, MilepostId m) throws GameException{
		if(!rails.containsKey(pid)){
			throw new GameException("PlayerNotFound");
		}
		return rails.get(pid).contains(m);
	}
	
	public boolean connects(String pid, MilepostId one, MilepostId two) throws GameException{
		if(!rails.containsKey(pid)){
			throw new GameException("PlayerNotFound");
		}
		return rails.get(pid).connects(one, two);
	}
	
	public boolean anyConnects(MilepostId one, MilepostId two){
		for(String pid : rails.keySet()){
			try {
				if(connects(pid, one, two)) return true;
			} catch (GameException e) {		} //the exception is thrown when the pid is not identified
		}
		return false;
	}
	
	/** Return the players who build this pair of mileposts, or null if none. 
	 * 
	 * @param one
	 * @param two
	 * @return
	 */
	public Set<String> getPlayers(MilepostId one, MilepostId two){
		Set<String> players = new HashSet<String>();
		for(String pid : rails.keySet()){
			try{
				if(connects(pid, one, two)) players.add(pid);
			}catch(GameException e) { }
		}
		return players;
	}

	public Rail getRail(String pid){
		return rails.get(pid);
	}
}
