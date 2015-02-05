package train;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import map.Milepost;
import map.MilepostId;
import map.MilepostIdShortFormTypeAdapter;
import map.MilepostShortFormTypeAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import player.GlobalRail;
import player.GlobalTrack;
import player.GlobalTrackTypeAdapter;
import player.Player;
import player.Rail;
import player.RailTypeAdapter;
import player.TurnData;
import reference.Card;
import reference.UpgradeType;


// Train game implementation class.
public class Game implements AbstractGame {

	public transient GameData gameData;
	private transient RuleSet ruleSet;
	
	//joinable can be represented by "turnData == null"
	private boolean ended; // game has ended
	private int turns; //the number of completed turns; 0, 1, and 2 are building turns

	//new structures
	private TurnData turnData; //data for the active player
	private int activeIndex;  //index of the active player in the pids list
	private List<String> pids; //list of the player-ids in order
	private Map<String, Player> players; //maps pid to player
	private GlobalRail globalRail; //all of the track drawn for this game, organized by pid
	
	//useful for undo and deletion
	private transient int transaction;
	private transient Date lastChange;
	private String name;
	private transient UndoRedoStack undoStack;
	private transient UndoRedoStack redoStack;
		
	private static Logger log = LoggerFactory.getLogger(Game.class);
	
	/** Default constructor for gson */
	public Game() {
		undoStack = new UndoRedoStack(GameException.NOTHING_TO_UNDO);
		redoStack = new UndoRedoStack(GameException.NOTHING_TO_REDO);
	}
	
	/** Constructor. 
	 * @param map
	 * @param ruleSet
	 */
	public Game(String name, GameData gameData, RuleSet ruleSet){
		this.gameData = gameData;
		this.ruleSet = ruleSet;
		this.name = name;
		
		//we don't construct a TurnData until the game starts 
		pids = new ArrayList<String>();
		players = new HashMap<String, Player>();
		globalRail = new GlobalRail();
		
		turns = 0;
		ended = false;
		transaction = 1;
		lastChange = new Date();
		undoStack = new UndoRedoStack(GameException.NOTHING_TO_UNDO);
		redoStack = new UndoRedoStack(GameException.NOTHING_TO_REDO);
	}
	
	@Override
	public void joinGame(String pid, String color) throws GameException {
		log.info("joinGame(pid={}, color={})", pid, color);		
		for (String oid : pids) {
			if (oid.equals(pid))
				throw new GameException(GameException.PLAYER_ALREADY_JOINED);
		}
		for(Player p : players.values()){
			if (p.color.equals(color))
				throw new GameException(GameException.COLOR_NOT_AVAILABLE);
		}

		pids.add(pid);
		
		Card[] cards = new Card[ruleSet.handSize];
		for(int i = 0; i < cards.length; i++){
			cards[i] = dealCard();
		}
		Player p = new Player(ruleSet, pid, color, cards);
		players.put(pid, p);
		globalRail.join(pid);
		registerTransaction();
	}

	@Override
	public boolean startGame(String pid, boolean ready) throws GameException {
		log.info("startGame(pid={}, ready={})", pid, ready);
		Player p = getPlayer(pid);
		p.readyToStart(ready);
		
		boolean start = true;
		for (Player player: players.values())
			if (!player.readyToStart())
				start = false;
		
		if (start) {	// All players are ready to start - start the game
			log.info("Starting game");
			
			// Turn starts with player with the highest cards
			// Reorder players list so it matches playering order
			String startid = pids.get(0);
			for (int i = 1; i < pids.size(); i++){
				if (players.get(startid).getMaxTrip() < players.get(pids.get(i)).getMaxTrip()) 
					startid = pids.get(i);
			}

			// Add all the players to the new players list, starting with the first player
			List<String> newPids = new ArrayList<String>();
			newPids.add(startid);
			for (String oid: pids){
				if (oid != startid)
					newPids.add(oid);
			}
			pids = newPids;
			turnData = new TurnData(name, ruleSet, players.get(startid));
			registerTransaction();
		}
		return start;
	}

	@Override
	public boolean testBuildTrack(String pid, MilepostId[] mileposts)
			throws GameException {
		logMileposts("testBuildTrack", pid, mileposts);
		checkActive(pid);
		Milepost[] mps = convert(mileposts);
		int cost = globalRail.checkBuild(pid, mps, ruleSet.multiPlayerTrack);
		return ((cost != -1) && turnData.checkSpending(cost));
	}

	@Override
	public void buildTrack(String pid, MilepostId[] mileposts)
			throws GameException {
		logMileposts("buildTrack", pid, mileposts);
		if(!testBuildTrack(pid, mileposts)){
			throw new GameException("Invalid Track");
		}
		String originalGameState = toString();
		turnData.startTurn();
		Milepost[] mps = convert(mileposts);
		int cost = globalRail.checkBuild(pid, mps, ruleSet.multiPlayerTrack);
		turnData.spend(cost);
		globalRail.build(pid, mps);
		registerTransaction(originalGameState);
	}

	@Override
	public void upgradeTrain(String pid, int train, UpgradeType upgrade)
			throws GameException {
		log.info("upgradeTrain(pid={}, train={}, upgradeType={})", pid, upgrade, train);
		checkActive(pid);
		String originalGameState = toString();
		turnData.startTurn();
		if(!turnData.checkSpending(20)) throw new GameException("ExceededAllowance");
		if(!getActivePlayer().testUpgradeTrain(train, upgrade)) throw new GameException("InvalidUpgrade");

		turnData.upgrade(train, upgrade);
		registerTransaction(originalGameState);
		turnData.startTurn();
	}

	@Override
	public void placeTrain(String pid, int train, MilepostId where)
			throws GameException {
		log.info("placeTrain(pid={}, train={}, where={})", pid, train, where);
		checkActive(pid);
		checkBuilding();
		String originalGameState = toString();
		turnData.startTurn();
		getPlayer(pid).placeTrain(gameData.getMap().getMilepost(where), train);
		registerTransaction(originalGameState);
	}

	public void testMoveTrain(String pid, int train, MilepostId[] mileposts) throws GameException{
		Player p = getPlayer(pid);
		if (mileposts.length == 0)	// null move is ok
			return;
		
		if(p.getTrain(train) == null || p.getTrain(train).getLocation() == null) 
			throw new GameException(GameException.INVALID_MOVE);

		MilepostId[] mps = new MilepostId[mileposts.length + 1];
		
		mps[0] = p.getTrain(train).getLocation().id;
		for(int i = 0; i < mileposts.length; i++){ 
			mps[i + 1] = mileposts[i];
		}
		
		// Cases:
		// 1. All moves are on active player's track, or on track that has already been rented
		// 2. All moves are on a new rental player's track
		// Moving on free track (through major cities) always ok
		Set<String> rid = null;
		for (int i = 0; i < mps.length - 1; ++i) {
			Set<String> owners = globalRail.getPlayers(mps[i], mps[i + 1]);
			if (owners == null) {  // no track built - check for free track (e.g. though major city)
				Milepost m1 = gameData.getMap().getMilepost(mps[i]);
				Milepost m2 = gameData.getMap().getMilepost(mps[i + 1]);
				if (!m1.isMajorCity() || !m2.isMajorCity() || !m1.isNeighbor(mps[i + 1]))
					throw new GameException(GameException.INVALID_MOVE);
			}
			else {	// make sure we aren't doing travel on multiple player's track in a single move
				if (rid == null) 
					rid = owners;
				else 
					rid.retainAll(owners);
				}
		}
		if (rid != null && rid.size() != 1)	// we've rented from more than one player in a single move
			throw new GameException(GameException.INVALID_MOVE);

		// Any ferry crossing must be the first milepost of the move
		boolean ferryCrossing = gameData.getMap().getMilepost(mps[0]).isNeighborByFerry(mps[1]);
		if (ferryCrossing && turnData.getMovesMade(0) > 0)
			throw new GameException(GameException.INVALID_MOVE);
		for (int i = 1; i < mps.length - 1; i++) { 
			if (gameData.getMap().getMilepost(mps[i]).isNeighborByFerry(mps[i+1]))
				throw new GameException(GameException.INVALID_MOVE);
		}
		
		// Make sure we didn't try to move too many mileposts
		int max = p.getMaxSpeed(train);
		if (ferryCrossing) max /= 2;
		if (!turnData.checkMovesLength(train, mileposts.length, max)) 
			throw new GameException(GameException.INVALID_MOVE);
	}
	
	@Override
	public void moveTrain(String pid, int train, MilepostId[] mileposts) throws GameException {
		logMileposts("moveTrain", pid, mileposts);
		checkActive(pid);
		checkBuilding();
		int maxMoves = getPlayer(pid).getMaxSpeed(train);
		testMoveTrain(pid, train, mileposts);
		
		String originalGameState = toString();
		turnData.startTurn();
		
		Player activePlayer = getPlayer(pid);

		// Handle track rental
		MilepostId previous = activePlayer.getTrain(train).getLocation().getMilepostId();
		for (int i = 0; i < mileposts.length; ++i) {
			Set<String> owners = globalRail.getPlayers(previous, mileposts[i]);
			if (owners.isEmpty() && !owners.contains(pid)) {  
				// TODO - better logic for picking owner when multiple choices for rental
				String owner = owners.iterator().next();
				if (!turnData.rentedFrom(owner))
					turnData.rent(owner);
			}
			previous = mileposts[i];
		}

		boolean ferryCrossing = activePlayer.getTrain(train).getLocation().isNeighborByFerry(mileposts[0]);
		activePlayer.moveTrain(train, gameData.getMilepost(mileposts[mileposts.length - 1]), mileposts.length);
		if (ferryCrossing) 
			turnData.ferry();

		turnData.move(train, maxMoves, activePlayer.getTrain(train).getLocation().id, mileposts);
		registerTransaction(originalGameState);
	}

	@Override
	public void pickupLoad(String pid, int train, String load)
			throws GameException {
		log.info("pickupLoad(pid={}, train={}, load={})", pid, train, load);
		checkActive(pid);
		checkBuilding();
		String originalGameState = toString();
		turnData.startTurn();
		getActivePlayer().pickupLoad(train, load);
		registerTransaction(originalGameState);
	}

	@Override
	public void deliverLoad(String pid, int train, String load, int card)
			throws GameException {
		log.info("deliverLoad(pid={}, train={}, load={})", pid, train, load);
		checkActive(pid);
		checkBuilding();
		String originalGameState = toString();
		turnData.startTurn();
		int deposit = getActivePlayer().deliverLoad(card, train, dealCard());
		turnData.deliver(deposit);
		registerTransaction(originalGameState);
	}

	@Override
	public void dumpLoad(String pid, int train, String load)
			throws GameException {
		log.info("dumpLoad(pid={}, train={}, load={})", pid, train, load);
		checkActive(pid);
		String originalGameState = toString();
		turnData.startTurn();
		getActivePlayer().dropLoad(train, load);
		registerTransaction(originalGameState);
		
	}

	@Override
	public void turnInCards(String pid) throws GameException {
		log.info("turnInCards requestText: pid={}", pid);
		checkActive(pid);
		if(turnData.turnInProgress()) 
			throw new GameException("TurnAlreadyStarted");
		
		String origState = toString();

		Card[] cards = new Card[ruleSet.handSize];
		for(int i = 0; i < cards.length; i++){
			cards[i] = dealCard();
		}
		getActivePlayer().turnInCards(cards);
		endTurn(pid);
		registerTransaction(origState);
	}
	
	/** To undo, clients should first save off the original game state, 
	 * then do whatever action they're doing, then call saveForUndo. That
	 * way if the action throws an exception, it won't go on the undo stack.
	 * @param gameState
	 */
	private void saveForUndo(String gameState) {
		redoStack.clear();
		undoStack.push(gameState);
	}
	
	@Override
	public Game undo() throws GameException {
		log.info("undo");
		String originalGameState = toString();
		String gameState = undoStack.pop();
		log.debug("Serialized game: {}", gameState);
		Game newGame = Game.fromString(gameState, this);
		newGame.registerTransaction();
		newGame.redoStack.push(originalGameState);
		return newGame;
	}
	
	@Override
	public Game redo() throws GameException {
		log.info("redo");
		String originalGameState = toString();
		String gameString = redoStack.pop();
		Game newGame = Game.fromString(gameString, this);
		newGame.registerTransaction();
		newGame.undoStack.push(originalGameState);
		return newGame;
	}

	@Override
	public void endTurn(String pid) throws GameException {
		log.info("endTurn(pid={},activeIndex={})", pid, activeIndex);

		String next = pid;
		switch(turns){
			case 0:
				if (activeIndex == players.size() - 1){	// player goes again
					turns++;
				}else{
					next = pids.get(activeIndex + 1);
					activeIndex++;
				}
				break;
			case 1:
				if(activeIndex == 0){
					turns++;
				}else{
					next = pids.get(activeIndex - 1);
					activeIndex--;
				}
				break;
			default:
				if (activeIndex == pids.size() - 1) {
					turns++;
					next = pids.get(0);
					activeIndex = 0;
				}
				else {
					next = (pids.get(activeIndex + 1));
					activeIndex++;
				}
			}
		
		calculateRent(players.get(pid));
		turnData.endTurn(next, players);
			
		registerTransaction();
		undoStack.clear();
		
		// If the player has resigned, skip their turn.
//		if (active != null && active.hasResigned())
//			endTurn(active.getPid());
	}

	private void calculateRent(Player player) {
		MilepostId previous = null;
		for (List<MilepostId> mileposts: turnData.getTrainMoves())  {
			Set<String> requiredOwners = new HashSet<String>();
			Set<String> optionalOwners = new HashSet<String>();
			if (mileposts.isEmpty())	// no move, no rent
				continue;
			previous = mileposts.get(0);
			// First step is to identify all those we MUST rent from in order to travel
			// If there are multiple players that have built track we are moving on, 
			// add them to the optional owners list. We'll have to figure out later which
			// optional owner to pick to rent from.
			for (int i = 1; i < mileposts.size(); ++i) {
				Set<String> owners = globalRail.getPlayers(previous, mileposts.get(i));
				if (owners != null && !owners.contains(player.getPid())) { 
					if (owners.size() == 1) 
						requiredOwners.add(owners.iterator().next());
					else {
						for (String owner: owners)
							if (!requiredOwners.contains(owner))
								optionalOwners.add(owner);
					}
				}
				previous = mileposts.get(i);
			}
			// Remove optional owners that we have to rent from anyway because they
			// are required.
			if (optionalOwners.size() > 0) {
				optionalOwners.clear();
				previous = mileposts.get(0);
				for (int i = 1; i < mileposts.size(); ++i) {
					Set<String> owners = globalRail.getPlayers(previous, mileposts.get(i));
					if (!owners.isEmpty() && !owners.contains(player.getPid())) {
						boolean foundOwner = false;
						for (String owner: owners) {
							if (requiredOwners.contains(owner)) {
								foundOwner = true;
								break;
							}
						}
						if (!foundOwner)
							optionalOwners.addAll(owners);
					}
					previous = mileposts.get(i);
				}
			}
			// If we still have optional renters, we need to figure out the minimal set
			// For each optional owner, try making it required and see what the resulting count
			// of required owners is
			if (optionalOwners.size() > 0) {
				List<Set<String>> milepostOwners = new ArrayList<Set<String>>();
				previous = mileposts.get(0);
				for (int i = 1; i < mileposts.size(); ++i) {
					Set<String> owners = globalRail.getPlayers(previous, mileposts.get(i));
					if (owners.size() > 1 && !owners.contains(player.getPid())) {
						boolean foundOwner = false;
						for (String owner: owners)
							if (requiredOwners.contains(owner))
								foundOwner = true;
						if (!foundOwner) 
							milepostOwners.add(owners);
					}
					previous = mileposts.get(i);
				}
				while (milepostOwners.size() > 0)  {
					if (milepostOwners.size() == 1)	{ // all are equal, pick the first one in the set
						// TODO make this random
						requiredOwners.add(milepostOwners.iterator().next().iterator().next());
						break;
					}
					else {
						Map<String, Integer> frequencyCount = new HashMap<String, Integer>();
						// Pick most frequent one remaining, and add it
						for (Set<String> mpowners: milepostOwners) {
							for (String owner: mpowners)
								if (frequencyCount.containsKey(owner))
									frequencyCount.put(owner, frequencyCount.get(owner) + 1);
								else
									frequencyCount.put(owner, 1);
						}
						Map.Entry<String,Integer> mostFrequent = null;
						for (Map.Entry<String,Integer> entry: frequencyCount.entrySet())
							if (mostFrequent == null || entry.getValue() > mostFrequent.getValue())
								mostFrequent = entry;
						requiredOwners.add(mostFrequent.getKey());
					    for (Iterator<Set<String>> milepostIter = milepostOwners.iterator(); 
					    		milepostIter.hasNext(); ) {
					    	Set<String> mpowners = milepostIter.next();
							if (mpowners.contains(mostFrequent.getKey()))
								milepostIter.remove();
						}
					}
						
				}
			}
		for (String owner: requiredOwners)
			turnData.rent(owner);
	}
}
	
	public void resign(String pid) throws GameException { 
		log.info("resign requestText: pid={}", pid);
		if (pid.equals(turnData.getPid()))
			endTurn(pid);
		if(pids.indexOf(pid) < activeIndex) activeIndex--;
		getPlayer(pid).resign();
		pids.remove(pid);
		undoStack.clear();
		redoStack.clear();
	}
	
	@Override
	public boolean endGame(String pid, boolean ready) throws GameException {
		log.info("endGame(pid={}, ready={})", pid, ready);
		Player p = getPlayer(pid);
		p.readyToEnd(ready);
		
		boolean end = true;
		for (Player player: players.values())
			if (!player.readyToEnd())
				end = false;
		
		if (end) {	// All players are ready to end - end the game
			log.info("ending game");
			ended = true;
//			setActive(null);
		}
		registerTransaction();
		return ended;
	}
	
	public String toString() {
		GsonBuilder gsonBuilder = new GsonBuilder();
		gsonBuilder.registerTypeAdapter(Milepost.class, new MilepostShortFormTypeAdapter(this));
		gsonBuilder.registerTypeAdapter(MilepostId.class, new MilepostIdShortFormTypeAdapter());
		gsonBuilder.registerTypeAdapter(Rail.class, new RailTypeAdapter());
		gsonBuilder.registerTypeAdapter(GlobalTrack.class, new GlobalTrackTypeAdapter());
		Gson gson = gsonBuilder.create();
		return gson.toJson(this);
	}
	
	//serialization
	public static Game fromString(String gameString, Game refGame) {
		GsonBuilder gsonBuilder = new GsonBuilder();
		gsonBuilder.registerTypeAdapter(Milepost.class, new MilepostShortFormTypeAdapter(refGame));
		gsonBuilder.registerTypeAdapter(MilepostId.class, new MilepostIdShortFormTypeAdapter());
		gsonBuilder.registerTypeAdapter(Rail.class, new RailTypeAdapter());
		gsonBuilder.registerTypeAdapter(GlobalTrack.class, new GlobalTrackTypeAdapter());
		Gson gson = gsonBuilder.create();
		log.info("undo " + gameString);
		Game newGame = gson.fromJson(gameString, Game.class);
		
		// Must do fixups on the new game, since not all fields are serialized.
		newGame.gameData = refGame.gameData;
		newGame.ruleSet = refGame.ruleSet;
		newGame.transaction = refGame.transaction;
		newGame.lastChange = refGame.lastChange;
		newGame.undoStack = refGame.undoStack;
		newGame.redoStack = refGame.redoStack;
//		newGame.globalRail = new HashMap<Milepost, Set<Rail.Track>>();
//		for (Player p: newGame.players) 
//			p.fixup(newGame, newGame.globalRail);
//		newGame.setActive(newGame.players.get(refGame.activeIndex));
		
		return newGame;
	}

	public Player getPlayer(String pid) throws GameException {
		if(players.containsKey(pid)) return players.get(pid);
		throw new GameException(GameException.PLAYER_NOT_FOUND);
	}
	
	public String getLastPid(){	return pids.get(pids.size() - 1); }
	
	public Collection<Player> getPlayers(){ return players.values(); }
	
	public Set<String> getPids(){ return players.keySet(); }
	
	public int getTurns() { return turns; }
	
	private void checkActive(String pid) throws GameException {
		if(turnData == null)
			throw new GameException("GameNotStarted");
		if (!(pid.equals(turnData.getPid()))) 
			throw new GameException(GameException.PLAYER_NOT_ACTIVE);
	}

	private void checkBuilding() throws GameException{
		if(turns < 3) throw new GameException(GameException.BUILDING_TURN);
	}

	/** Returns player whose turn it is */
	public String getActivePid() { 
		if(turnData == null) return pids.get(0);
		return turnData.getPid();
	}
	
	public Player getActivePlayer(){
		return players.get(getActivePid());
	}
	
	public TurnData getTurnData(){ return turnData; }
	
	public GlobalRail getGlobalRail() {return globalRail; }
	
	public RuleSet getRuleSet() { return ruleSet; }
	
	public boolean isJoinable() { return turnData == null; }
	
	public boolean isOver() { return ended; }

	public int transaction() { return transaction; }
	
	public Date lastChangeDate() { return lastChange; }
	
	public String name() { return name; }
	
	private void registerTransaction() {
		lastChange = new Date();
		++transaction;
	}
	
	private void registerTransaction(String originalGameState) {
		saveForUndo(originalGameState);
		registerTransaction();
	}
	
	private Card dealCard() {
		return gameData.draw();
	}

	private Milepost[] convert(MilepostId[] m){
		Milepost[] mps = new Milepost[m.length];
		for(int i = 0; i < m.length; i++){
			mps[i] = gameData.getMap().getMilepost(m[i]);
		}	
		return mps;
	}
	
	private void logMileposts(String msg, String pid, MilepostId[] mileposts) {
		log.info("{}(pid={}, length={}, mileposts=[", msg, pid, mileposts.length);
		for (int i = 0; i < mileposts.length; ++i)
			log.info("{}, ", mileposts[i]);
		log.info("])");
	}
	

	
}
