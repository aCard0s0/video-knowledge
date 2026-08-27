import { $t as not, Qt as filter, R as raceWith, cr as __read, lr as __spreadArray, tn as argsOrArgArray } from "./zipWith-CSXmh3Jb.js";
//#region ../../../../../../../../../Users/acardoso/Documents/Github/video-knowledge/applications/webapp/node_modules/rxjs/dist/esm5/internal/operators/partition.js
function partition(predicate, thisArg) {
	return function(source) {
		return [filter(predicate, thisArg)(source), filter(not(predicate, thisArg))(source)];
	};
}
//#endregion
//#region ../../../../../../../../../Users/acardoso/Documents/Github/video-knowledge/applications/webapp/node_modules/rxjs/dist/esm5/internal/operators/race.js
function race() {
	var args = [];
	for (var _i = 0; _i < arguments.length; _i++) args[_i] = arguments[_i];
	return raceWith.apply(void 0, __spreadArray([], __read(argsOrArgArray(args))));
}
//#endregion
export { partition as n, race as t };
