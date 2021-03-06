'use strict';

const common = require('../common.js');
const bench = common.createBenchmark(main, {
  millions: [2]
});

function main(conf) {
  const N = +conf.millions * 1e6;
  var n = 0;

  function cb1(arg1) {
    n++;
    if (n === N)
      bench.end(n / 1e6);
  }
  function cb2(arg1, arg2) {
    n++;
    if (n === N)
      bench.end(n / 1e6);
  }
  function cb3(arg1, arg2, arg3) {
    n++;
    if (n === N)
      bench.end(n / 1e6);
  }

  bench.start();
  for (var i = 0; i < N; i++) {
    if (i % 3 === 0)
      process.nextTick(cb3, 512, true, null);
    else if (i % 2 === 0)
      process.nextTick(cb2, false, 5.1);
    else
      process.nextTick(cb1, 0);
  }
}
