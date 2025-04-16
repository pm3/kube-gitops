package eu.aston.gitops.utils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

public class CronPattern {
	private final String expression;
	private final int[][] matchers;

	private static final ItemConf[] configs = new ItemConf[]{
			new ItemConf(0,59, null, LocalDateTime::getMinute),
			new ItemConf(0,23, null, LocalDateTime::getHour),
			new ItemConf(1,31, null, LocalDateTime::getDayOfMonth),
			new ItemConf(1, 12, new String[] { "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec" }, LocalDateTime::getMonthValue),
			new ItemConf(1, 7, new String[] { "sun", "mon", "tue", "wed", "thu", "fri", "sat" }, (l)->l.getDayOfWeek().getValue()),
	};

	public CronPattern(String expression) {
		if (expression == null || expression.trim().isEmpty()) {
			throw new IllegalArgumentException("expression is empty");
		}
		this.expression = expression;
		this.matchers = parse(expression);
	}

	public static <T> Stream<T> matchAll(List<T> l, Function<T, CronPattern> getter){
		LocalDateTime now = LocalDateTime.now();
		int[] items = Arrays.stream(configs).mapToInt(config -> config.getter().apply(now)).toArray();
		return l.stream().filter(e->{
			CronPattern p = getter.apply(e);
			return p!=null && p.match(items);
		});
	}

	public boolean match(int[] dateItems) {
		for(int i=0; i<configs.length; i++){
			int[] matcher = matchers[i];
			if(matcher.length!=0 && Arrays.binarySearch(matcher, dateItems[i])<0) return false;
		}
		return true;
	}

	public boolean match(LocalDateTime localDateTime) {
		for(int i=-0; i<configs.length; i++){
			Integer akt = configs[i].getter().apply(localDateTime);
			int[] matcher = matchers[i];
			if(matcher.length!=0 && Arrays.binarySearch(matcher, akt)<0) return false;
		}
		return true;
	}

	private static int[][] parse(String expression) {
		String[] compositeItems = expression.trim().split("\\s+");
		if (compositeItems.length != 5){
			throw new IllegalArgumentException("not match (min hour day month week) in expression " + expression);
		}
		int[][] _matchers = new int[configs.length][];
		for (int i=0;i<compositeItems.length; i++){
			try{
				ItemConf conf = configs[i];
				Set<Integer> valueSet = parseCompositeItem(compositeItems[i], conf.eMin(), conf.eMax(), conf.aliases());
				int[] intValues = valueSet.stream().mapToInt(Integer::intValue).sorted().toArray();
				_matchers[i] = intValues;
			} catch (IllegalArgumentException e){
				throw new IllegalArgumentException("parse item "+(i+1)+" = '"+compositeItems[i]+"' error "+e.getMessage()+" in expression '"+expression+"'");
			} catch (Exception e){
				throw new IllegalArgumentException("parse item "+(i+1)+" = '"+compositeItems[i]+"' in expression '"+expression+"'");
			}
		}
		return _matchers;
	}

	private static Set<Integer> parseCompositeItem(String expr, int eMin, int eMax, String[] aliases) {
		if ("*".equals(expr)) return new HashSet<>();
		String[] exprItems = expr.split(",");
		Set<Integer> matches = new HashSet<>();
		for (String item : exprItems) {
			if ("*".equals(item)) return new HashSet<>();
			String[] rangeItems = item.split("-");
			String[] stepItems = item.split("/");
			if (rangeItems.length == 2) {
				int rMin = parseValue(rangeItems[0], eMin, eMax, aliases);
				int rMax = parseValue(rangeItems[1], eMin, eMax, aliases);
				if(rMin>rMax) throw new IllegalArgumentException("min/max");
				minMaxStep(rMin, rMax, 1, matches);
			} else if (stepItems.length == 2) {
				int start = Integer.parseInt(stepItems[0]);
				int step = Integer.parseInt(stepItems[1]);
				minMaxStep(start, eMax, step, matches);
			} else {
				matches.add(parseValue(item, eMin, eMax, aliases));
			}
		}
		return matches;
	}

	private static int parseValue(String item, int eMin, int eMax, String[] aliases) {
		if(aliases!=null){
			for(int i=0; i<aliases.length; i++){
				if(item.equals(aliases[i])){
					return eMin+i;
				}
			}
		}
		int val = Integer.parseInt(item);
		if(val<eMin) throw new IllegalArgumentException("min");
		if(val>eMax) throw new IllegalArgumentException("max");
		return val;
	}

	private static void minMaxStep(int min, int max, int step, Set<Integer> matches){
		boolean empty = true;
		for (int i=min;i<=max; i+=step) {
			matches.add(i);
			empty = false;
		}
		if(empty) throw new IllegalArgumentException("empty_range");
	}

	@Override
	public String toString() {
		return expression;
	}

	private record ItemConf(int eMin, int eMax, String[] aliases, Function<LocalDateTime, Integer> getter){}
}
