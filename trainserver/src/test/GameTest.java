package test;

import static org.junit.Assert.*;

import map.MilepostId;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import train.Game;
import train.TrainServer;


public class GameTest {

	private static Logger log = LoggerFactory.getLogger(GameTest.class);
	
	// Send a stream of requests to the server check the results
	@Test
	public void testTrain() throws Exception {
		String jsonPayload = "{\"messageType\":\"newGame\", \"pid\":\"Adam\", \"color\":\"blue\", \"ruleSet\":\"anythingGoes\", \"gameType\":\"africa\"}";
        String responseMessage = TrainServer.newGame(jsonPayload);
        String gid = responseMessage.substring(8, responseMessage.length() - 2);
        Game game = TrainServer.getGame(gid);
        assertTrue(game != null);
        game.joinGame("Sandra", "green");
        game.joinGame("Sandy", "red");
        game.joinGame("Robin", "purple");
        game.startGame("Adam");
        
        String activePlayer = game.getActivePlayer().name;
        log.info("Active player is {}", activePlayer);
        MilepostId[] mileposts;
        mileposts = new MilepostId[]{ new MilepostId(2, 20), new MilepostId(3, 20), new MilepostId(4, 20),
        	new MilepostId(4, 21), new MilepostId(4, 22), new MilepostId(4, 23), new MilepostId(4, 24)};
        game.buildTrack(activePlayer, mileposts);
        
        game.startTrain(activePlayer, new MilepostId(0, 0));
        game.moveTrain(activePlayer, mileposts);
        game.pickupLoad(activePlayer, "Dakar", "turnips");
        game.pickupLoad(activePlayer, "Dakar", "iron");
        game.moveTrain(activePlayer, mileposts);
        game.deliverLoad(activePlayer, "Freetown", "turnips");
        game.dumpLoad(activePlayer, "iron");
        game.endTurn(activePlayer);
        game.endGame(activePlayer);
    }





}