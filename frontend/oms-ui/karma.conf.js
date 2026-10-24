// Karma configuration. Set CHROME_BIN to a Chrome/Chromium/Edge binary, then:
//   npm test            (headless, single run, no sandbox)
// On this dev machine: CHROME_BIN="C:\Program Files\Google\Chrome\Application\chrome.exe"
module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
    ],
    client: {
      jasmine: { random: true },
      clearContext: false,
    },
    jasmineHtmlReporter: { suppressAll: true },
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage/oms-ui'),
      subdir: '.',
      reporters: [{ type: 'html' }, { type: 'text-summary' }],
    },
    reporters: ['progress', 'kjhtml'],
    browsers: ['ChromeHeadlessNoSandbox'],
    customLaunchers: {
      ChromeHeadlessNoSandbox: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--window-size=1280,900'],
      },
    },
    restartOnFileChange: true,
    browserNoActivityTimeout: 120000,
    captureTimeout: 120000,
  });
};
