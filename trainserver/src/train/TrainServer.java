package train;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import map.Milepost;
import map.MilepostId;
import map.TrainMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import player.Player;
import player.Train;

import reference.Card;
import reference.City;
import reference.UpgradeType;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

/** Maps incoming data from JSON strings into calls on a specific game. Maintains the list 
 * of in progress games.
 */
public class TrainServer {
	private static Logger log = LoggerFactory.getLogger(TrainServer.class);

	private static RandomString gameNamer = new RandomString(8); // use for
																	// generating
																	// (semi)unique
																	// gameIds

	static Map<String, Game> games = new HashMap<String, Game>(); // games currently in progress;
	
	static public Game getGame(String gid) {
		return games.get(gid);		
	}
	
	static class CardStatus {
		public CardTripStatus[] trips;
		CardStatus() {}
	}
	
	static class CardTripStatus {
		public String dest;
		public String load;
		public int cost;
		CardTripStatus() {}
	}
	
	static class PlayerStatus {
		public String pid;
		public String color;
		public Train[] trains;
		public int money;
		public Map<MilepostId, Set<MilepostId>> rail;
		//public CardStatus[] hand;
		public Card[] hand;
		public int spendings;
		public int movesMade;
		PlayerStatus() {}
	}
	
	static class GameStatus {
		public String gid;
		public String activeid;
		public String lastid;
		public List<PlayerStatus> players; //in turn order beginning with the active player
		public int transaction;
		GameStatus() {}
	}
	
	private static class MilepostSerializer implements JsonSerializer<Milepost> {
		  public JsonElement serialize(Milepost src, Type typeOfSrc, JsonSerializationContext context) {
		    return new JsonPrimitive(src.toString());
		  }		
	}
	
	static class StatusRequest {
		public String gid;
		StatusRequest() {}
	}
	
	static public String status(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		StatusRequest data = gson.fromJson(requestText, StatusRequest.class);
		String gid = data.gid;
		
		GsonBuilder gsonBuilder = new GsonBuilder();
		GameStatus status = new GameStatus();
		Game game = getGame(gid);
		status.gid = gid;
		status.players = new ArrayList<PlayerStatus>();
		status.transaction = game.transaction();
		Player p = game.getActivePlayer();
		if(game.getLastPlayer() == null) {
			status.activeid = "";
			status.lastid = "";
		} else {
			status.activeid = game.getActivePlayer().name;
			status.lastid = game.getLastPlayer().name;
		}
		do {
			PlayerStatus pstatus = new PlayerStatus();
			pstatus.pid = p.name;
			pstatus.color = p.color;
			pstatus.trains = p.getTrains();
			pstatus.money = p.getMoney();
			pstatus.spendings = p.getSpending();
			pstatus.movesMade = p.getMovesMade();
			
			Map<Milepost, Set<Milepost>> railMileposts = p.getRail().getRail();
			Map<MilepostId, Set<MilepostId>> railIds = new HashMap<MilepostId, Set<MilepostId>>();
			for(Milepost outer : railMileposts.keySet()){
				Set<MilepostId> inner = new HashSet<MilepostId>();
				railIds.put(new MilepostId(outer.x, outer.y), inner);
				for(Milepost m : railMileposts.get(outer)){
					inner.add(new MilepostId(m.x, m.y));
				}
			}
			pstatus.rail = railIds;
			pstatus.hand = p.getCards();
			status.players.add(pstatus);
			p = p.getNextPlayer();
		}while(p != game.getActivePlayer());
		
		return gsonBuilder.create().toJson(status);
	}
	
	static class ListRequest {
		public String listType;
		ListRequest() {}
	}
	
	static class ListResponse {
		public Set<String> gids;
		ListResponse() { gids = new HashSet<String>(); }
	}
	
	static public String list(String requestText) throws GameException {
		log.info("list requestText: {}", requestText);
		Gson gson = new GsonBuilder().create();
		ListRequest data = gson.fromJson(requestText, ListRequest.class);
		ListResponse responseData = new ListResponse();
		if (data.listType.equals("joinable")) {
			for (String gid : games.keySet())
				if (games.get(gid).isJoinable())
					responseData.gids.add(gid);
		}
		else if (data.listType.equals("resumeable")) {
			for (String gid : games.keySet())
				if (!games.get(gid).isJoinable())
					responseData.gids.add(gid);
		}
		else if (data.listType == "all")
			responseData.gids = games.keySet();
		String result = gson.toJson(responseData);
		log.info("list response {}", result);
		return gson.toJson(responseData);
	}
	
	static class NewGameData {
		//public String messageType;
		public String pid; // host playerId
		public String color; // color for track building
		public RuleSet ruleSet; // name for rules of the game
		public String gameType; // which game (Africa, Eurasia, etc.)
		
		NewGameData() {}
	}
	
	static class NewGameResponse {
		public String gid;
		public TrainMap.SerializeData mapData;
		public Collection<City> cities;	/** Cities indexed by city name, contains loads found in each city */
		public Map<String, Set<String>> loadset; /** Key=load, Value= cities where loads can be obtained */
		NewGameResponse() {}
	}
	
	static public String newGame(String requestText) throws GameException {			
		String gameId = null;
		Gson gson = new GsonBuilder().create();
		NewGameData data = gson.fromJson(requestText, NewGameData.class);
		
		
		GameData gameData = new GameData(data.gameType);
		if (data.ruleSet == null)
			data.ruleSet = new RuleSet(4, 70, 1);
		Game game = new Game(gameData, data.ruleSet);
		gameId = gameNamer.nextString();
		games.put(gameId, game);
		game.joinGame(data.pid, data.color);

		// Send a JSON response that has gid, serialized map data, list of cities and loads
		GsonBuilder gsonBuilder = new GsonBuilder();
		gsonBuilder.registerTypeAdapter(Milepost.class, new MilepostSerializer());
		NewGameResponse response = new NewGameResponse();
		response.mapData = gameData.map.getSerializeData();
		response.cities = gameData.cities.values();
		// Convert from loads to set of cities to loads to set of city names
		response.loadset = new HashMap<String, Set<String>>();
		for (String load: gameData.loads.keySet()) {
			Set<String> cities = new HashSet<String>();
			for (City city:gameData.loads.get(load))
				cities.add(city.name);
			response.loadset.put(load, cities);
		}
		response.gid = gameId;
		return gsonBuilder.create().toJson(response);
	}

	static class JoinGameData {
		public String gid;
		public String pid;
		public String color;
		}
	
	static public void joinGame(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		JoinGameData data = gson.fromJson(requestText, JoinGameData.class);
		Game game = games.get(data.gid);
		if (game == null)
		{
			log.warn("Can't find game {}", data.gid);
			for (String key: games.keySet())
				log.info("found gid {}", key);
			throw new GameException(GameException.GAME_NOT_FOUND);
		}
		game.joinGame(data.pid, data.color);

	}

	static class StartGameData {
		public String gid;
		public String pid;
	}

	static public void startGame(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		StartGameData data = gson.fromJson(requestText, StartGameData.class);
		Game game = games.get(data.gid);
		if (game == null)
			throw new GameException(GameException.GAME_NOT_FOUND);
		game.startGame(data.pid);
	}

	static class BuildTrackData {
		public String gid;
		public String pid;
		public MilepostId[] mileposts;
	}

	static public void buildTrack(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		BuildTrackData data = gson.fromJson(requestText, BuildTrackData.class);
		Game game = games.get(data.gid);
		if (game == null)
			throw new GameException(GameException.GAME_NOT_FOUND);
		game.buildTrack(data.pid, data.mileposts);
	}

	static class UpgradeTrainData {
		public String gid;
		public String pid;
		public String upgradeType;
		public int train;
		
		public UpgradeTrainData() {
			train = 0;
		}
	}

	static public void upgradeTrain(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		UpgradeTrainData data = gson.fromJson(requestText,
				UpgradeTrainData.class);
		Game game = games.get(data.gid);
		if (game == null)
			throw new GameException(GameException.GAME_NOT_FOUND);
		if (!data.upgradeType.equals("Capacity") && !data.upgradeType.equals("Speed"))
			throw new GameException(GameException.INVALID_UPGRADE);
		game.upgradeTrain(data.pid, data.train,
				data.upgradeType.equals("Capacity") ? UpgradeType.CAPACITY
						: UpgradeType.SPEED);
	}

	static class StartTrainData {
		public String gid;
		public String pid;
		public int train;
		public MilepostId where;
	}

	static public void startTrain(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		StartTrainData data = gson.fromJson(requestText, StartTrainData.class);
		Game game = games.get(data.gid);
		if (game == null)
			throw new GameException(GameException.GAME_NOT_FOUND);
		game.startTrain(data.pid, data.train, data.where);
	}

	static class MoveTrainData {
		public String gid;
		public String pid;
		public int train;
		public MilepostId[] mileposts;
	}

	static public void moveTrain(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		MoveTrainData data = gson.fromJson(requestText, MoveTrainData.class);
		Game game = games.get(data.gid);
		if (game == null)
			throw new GameException(GameException.GAME_NOT_FOUND);
		game.moveTrain(data.pid, data.train, data.mileposts);
	}

	static class PickupLoadData {
		public String gid;
		public String pid;
		public int train;
		public String city;
		public String load;
	}

	static public void pickupLoad(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		PickupLoadData data = gson.fromJson(requestText, PickupLoadData.class);
		Game game = games.get(data.gid);
		if (game == null)
			throw new GameException(GameException.GAME_NOT_FOUND);
		game.pickupLoad(data.pid, data.train, data.load);
	}

	static class DeliverLoadData {
		public String gid;
		public String pid;
		public int train;
		public String city;
		public String load;
		public int card;
	}

	static public void deliverLoad(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		DeliverLoadData data = gson
				.fromJson(requestText, DeliverLoadData.class);
		Game game = games.get(data.gid);
		if (game == null)
			throw new GameException(GameException.GAME_NOT_FOUND);
		game.deliverLoad(data.pid, data.train, data.load, data.card);
	}

	static class DumpLoadData {
		public String gid;
		public String pid;
		public int train;
		public String load;
	}

	static public void dumpLoad(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		DumpLoadData data = gson.fromJson(requestText, DumpLoadData.class);
		Game game = games.get(data.gid);
		if (game == null)
			throw new GameException(GameException.GAME_NOT_FOUND);
		game.dumpLoad(data.pid, data.train, data.load);
	}

	static class EndTurnData {
		public String gid;
		public String pid;
	}

	static public void endTurn(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		EndTurnData data = gson.fromJson(requestText, EndTurnData.class);
		Game game = games.get(data.gid);
		if (game == null)
			throw new GameException(GameException.GAME_NOT_FOUND);
		game.endTurn(data.pid);
	}

	static class EndGame {
		public String gid;
		public String pid;
	}

	static public void endGame(String requestText) throws GameException {
		Gson gson = new GsonBuilder().create();
		EndGame data = gson.fromJson(requestText, EndGame.class);
		Game game = games.get(data.gid);
		if (game == null)
			throw new GameException(GameException.GAME_NOT_FOUND);
		game.endGame(data.pid);
	}
	
}
