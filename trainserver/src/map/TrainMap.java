package map;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reference.City;
import train.GameException;

public final class TrainMap {
	private final Map<MilepostId, Milepost> milepostIndex;
	private final Map<String, MilepostId> cityLocations;

	public class SerializeData {
		public List<Milepost> orderedMileposts;	// used for map serialization
		public int mpWidth = 0;	// used for map serialization
		public int mpHeight = 0;	// used for map serialization
		public int leftOffset = 0;	// whitespace at left edge of map
		public int topOffset = 0; 	// whitespace at top edge of map
		public int mapWidth = 0;	// width of map excluding all whitespace
		public int mapHeight = 0;	// height of map excluding all whitespace
		
		public SerializeData() {
			orderedMileposts = new ArrayList<Milepost>();
		}
	}
	private SerializeData serializeData;
	
	private static Logger log = LoggerFactory.getLogger(TrainMap.class);

	/** Initialize the map data from a csv formatted string, where mileposts
	 * are stored as fields in a 2-dimensional array, where each entry is one of:
	 * b - blank (milepost does not exist here)
	 * l - lake (milepost is underwater : does not exist here)
	 * m - normal milepost
	 * d - desert milepost
	 * h - mountain milepost
	 * a - alpine milepost
	 * f - forest milepost
	 * j - jungle milepost
	 * u - chunnel milepost
	 * y - placeholder for a ferry edge - milepost does not exist here
	 * cName - city milepost, where Name is name of the city
	 * ccName - major city milepost, where Name is name of the city
	 * 
	 * @param mapData
	 * @throws IOException  
	 * @throws GameException 
	 */
	public TrainMap(BufferedReader mapReader, BufferedReader riverReader, BufferedReader seaReader, 
			BufferedReader ferryReader, Map<String, City> cities) throws IOException, GameException {
		milepostIndex = new HashMap<MilepostId, Milepost>();
		cityLocations = new HashMap<String, MilepostId>();
		serializeData = new SerializeData();

		generateMileposts(mapReader, cities);
		generateEdges(riverReader, seaReader, ferryReader);
	}
	
	public SerializeData getSerializeData() {
		SerializeData data = serializeData;
		return data;
	}
	
	public void generateMileposts(BufferedReader mapDataReader, Map<String, City> cities) 
			throws IOException, GameException {
		int y = 0;
		String firstline = mapDataReader.readLine(); // left, top, width, height
		String [] firstFields = firstline.split(",");
		serializeData.leftOffset = Integer.parseInt(firstFields[0]);
		serializeData.topOffset = Integer.parseInt(firstFields[1]);
		serializeData.mapWidth = Integer.parseInt(firstFields[2]);
		serializeData.mapHeight = Integer.parseInt(firstFields[3]);
		String line = mapDataReader.readLine();	// skip over the row header
		while ((line = mapDataReader.readLine()) != null) {
		   // process the line.
			String [] fields = line.split(",");
			int x = 0;
			for (String field: fields) {
				Milepost.Type mpType = Milepost.Type.BLANK;
				City city = null;
				switch (field)  {
				case "b":
				case "l":	/* unbuildable lake equivalent to blank */
					mpType = Milepost.Type.BLANK;
					break;
				case "y":
					mpType = Milepost.Type.BLANK;
					break;
				case "d":
					mpType = Milepost.Type.DESERT;
					break;
				case "j": 
					mpType = Milepost.Type.JUNGLE;
					break;
				case "f":
					mpType = Milepost.Type.FOREST;
					break;
				case "h":
					mpType = Milepost.Type.MOUNTAIN;
					break;
				case "a":
					mpType = Milepost.Type.ALPINE;
					break;
				case "m":
					mpType = Milepost.Type.NORMAL;
					break;
				case "u":
					mpType = Milepost.Type.CHUNNEL;
					break;
				default:
					String cityName;
					if (field.startsWith("cc")) {
						mpType = Milepost.Type.MAJORCITY;
						cityName = field.substring(2);
					} 
					else if (field.startsWith("c")) {
						mpType = Milepost.Type.CITY;
						cityName = field.substring(1);
					}
					else {
						log.warn("Unknown milepost type {}", field);
						throw new GameException(GameException.BAD_MAP_DATA);
					}
					city = cities.get(cityName);
					if (city == null)
						log.warn("City milepost for " + cityName + " on map missing corresponding city in city list");
					cityLocations.put(cityName, new MilepostId(x,y));
					break;
				}
				log.debug("Found milepost type {} at [{}, {}]", mpType, x, y);
				Milepost mp = new Milepost(x, y, city, mpType);
				milepostIndex.put(new MilepostId(x,y), mp);
				serializeData.orderedMileposts.add(mp);
				++x;
			}
			serializeData.mpWidth = x;
			++y;
		}
		serializeData.mpHeight = y;
	}
	
	private boolean isCrossing(MilepostId source, MilepostId destination,
			Map<MilepostId, Set<MilepostId>> crossings) {
		// Source and destination can be any order in the crossings
		Set<MilepostId> crossingMPs = crossings.get(source);
		if (crossingMPs != null && crossingMPs.contains(destination))
			return true;
		crossingMPs = crossings.get(destination);
		if (crossingMPs != null && crossingMPs.contains(source))
			return true;
		
		return false;
	}
	
	private Edge generateEdge(Milepost source, MilepostId destinationId,
			Map<MilepostId, Set<MilepostId>> riverCrossings,
			Map<MilepostId, Set<MilepostId>> seaCrossings, Map<MilepostId, MilepostId[]> ferryCrossings) {
		Edge edge = null;
		Milepost destination = milepostIndex.get(destinationId);
//		MilepostId sourceId = new MilepostId(source.x, source.y);
		boolean isRiverCrossing = isCrossing(source.id, destinationId, riverCrossings);
		if (isRiverCrossing)
			log.debug("River crossing ({}, {} to ({}, {})", source.id.x, source.id.y, destinationId.x, destinationId.y);
		boolean isSeaCrossing = isCrossing(source.id, destinationId, seaCrossings);
		
		if(destination == null) return null;
		if(destination.type != Milepost.Type.BLANK) {
			edge = new Edge(destination, isRiverCrossing, isSeaCrossing);
			log.debug("Generating edge from milepost [{}, {}] to milepost [{},{}], cost {}", source.id.y, source.id.x,
					destination.id.y, destination.id.x, edge.cost);
		}
		return edge;
	}
	
	private Map<MilepostId, Set<MilepostId>>  readCrossings(BufferedReader reader) throws IOException, GameException {
		Map<MilepostId, Set<MilepostId>> crossings = new HashMap<MilepostId, Set<MilepostId>>();
		String line;
		while ((line = reader.readLine()) != null) {
			String[] fields = line.split(",");
			if (fields.length != 2) {
				log.error("More than two fields in crossing file");
				throw new GameException(GameException.BAD_MAP_DATA);
			}
			
			String[] mpsSource = fields[0].split(";");
			int xSource = Integer.parseInt(mpsSource[0]);
			int ySource = Integer.parseInt(mpsSource[1]);
			
			String[] mpsDestination = fields[1].split(";");
			int xDestination = Integer.parseInt(mpsDestination[0].trim());
			int yDestination = Integer.parseInt(mpsDestination[1].trim());
			MilepostId mpSource = new MilepostId(xSource, ySource);
			MilepostId mpDestination = new MilepostId(xDestination, yDestination);
			Set<MilepostId> dests = crossings.get(mpSource);
			if (dests == null) 	// add the first mapping for the milepost
				dests = new HashSet<MilepostId>();
			dests.add(mpDestination);
			log.debug("Adding crossing [{},{}] to [{},{}]", ySource, xSource, yDestination, xDestination);
			crossings.put(mpSource, dests);
		}
		return crossings;
	}
	
	private Map<MilepostId, MilepostId[]> readFerries(BufferedReader ferryReader) throws IOException, GameException{
		//ID -> ID && ID -> edge(marked /w target ID)
		Map<MilepostId, MilepostId[]> ferries = new HashMap<MilepostId, MilepostId[]>(); 
		String line = "";
		while((line = ferryReader.readLine()) != null){
			String[] splits = line.split(",");
			int[] data = new int[6];
			for(int i = 0; i < 6; i++){
				data[i] = Integer.parseInt(splits[i]);
			}
			MilepostId source = new MilepostId(data[0], data[1]);
			MilepostId dest = new MilepostId(data[3], data[4]);
			if(ferries.containsKey(source)){
				MilepostId[] targets = ferries.get(source);
				if(targets[data[2]] != null){
					throw new GameException("BAD_MAP_DATA");
				} targets[data[2]] = dest;
			}else{
				MilepostId[] targets = new MilepostId[6];
				ferries.put(source, targets);
				targets[data[2]] = dest;
			}
			if(ferries.containsKey(dest)){
				MilepostId[] targets = ferries.get(dest);
				if(targets[data[5]] != null){
					throw new GameException("BAD_MAP_DATA");
				} targets[data[5]] = source;
			}else{
				MilepostId[] targets = new MilepostId[6];
				targets[data[5]] = source;
				ferries.put(dest, targets);
			}
		}
		return ferries;
	}
	
	private void generateEdges(BufferedReader riverReader, BufferedReader seaReader, BufferedReader ferryReader) 
				throws IOException, GameException {
		Map<MilepostId, Set<MilepostId>> riverCrossings = readCrossings(riverReader);
		Map<MilepostId, Set<MilepostId>> seaInletCrossings = readCrossings(seaReader);
		Map<MilepostId, MilepostId[]> ferryCrossings = readFerries(ferryReader);
		
		for (MilepostId mpId: milepostIndex.keySet())  {
			Milepost mp = milepostIndex.get(mpId);
			Edge[] edges = new Edge[6];
			if (mp.type != Milepost.Type.BLANK) {
				if (mp.id.y % 2 == 0) {	// even row 
					edges[0] = generateEdge(mp, new MilepostId(mp.id.x, mp.id.y - 1), riverCrossings, seaInletCrossings, ferryCrossings);	// NE
					edges[1] = generateEdge(mp, new MilepostId(mp.id.x + 1, mp.id.y), riverCrossings, seaInletCrossings, ferryCrossings);		// E
					edges[2] = generateEdge(mp, new MilepostId(mp.id.x, mp.id.y + 1), riverCrossings, seaInletCrossings, ferryCrossings);	// SE
					edges[3] = generateEdge(mp, new MilepostId(mp.id.x - 1, mp.id.y + 1), riverCrossings, seaInletCrossings, ferryCrossings);		// SW
					edges[4] = generateEdge(mp, new MilepostId(mp.id.x - 1, mp.id.y), riverCrossings, seaInletCrossings, ferryCrossings);		// W
					edges[5] = generateEdge(mp, new MilepostId(mp.id.x - 1, mp.id.y - 1), riverCrossings, seaInletCrossings, ferryCrossings);		// NW
				} 
				else { 	// odd row
					edges[0] = generateEdge(mp, new MilepostId(mp.id.x + 1, mp.id.y - 1), riverCrossings, seaInletCrossings, ferryCrossings);	// NE
					edges[1] = generateEdge(mp, new MilepostId(mp.id.x + 1, mp.id.y), riverCrossings, seaInletCrossings, ferryCrossings);		// E
					edges[2] = generateEdge(mp, new MilepostId(mp.id.x + 1, mp.id.y + 1), riverCrossings, seaInletCrossings, ferryCrossings);	// SE
					edges[3] = generateEdge(mp, new MilepostId(mp.id.x, mp.id.y + 1), riverCrossings, seaInletCrossings, ferryCrossings);		// SW
					edges[4] = generateEdge(mp, new MilepostId(mp.id.x - 1, mp.id.y), riverCrossings, seaInletCrossings, ferryCrossings);		// W
					edges[5] = generateEdge(mp, new MilepostId(mp.id.x, mp.id.y - 1), riverCrossings, seaInletCrossings, ferryCrossings);		// NW
					}
				}
			if(ferryCrossings.containsKey(mpId)){
				MilepostId[] targets = ferryCrossings.get(mpId);
				for(int i = 0; i < 6; i++){
					if(targets[i] != null && milepostIndex.containsKey(targets[i])){
						log.debug("Generating ferry from milepost [{}, {}] to milepost [{},{}], overwriting edge {}", 
								mpId.y, mpId.x, targets[i].y, targets[i].x, edges[i]);
						edges[i] = new Ferry(milepostIndex.get(targets[i]));
					}
				}
			}
			mp.updateEdges(edges);
		}
	}
	
	public Milepost getMilepost(MilepostId id){
		return milepostIndex.get(id);
	}
}
