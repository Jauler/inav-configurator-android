import platform from './platform';

const dialog =  {
    showOpenDialog: async function (options) {
        return platform.showOpenDialog(options);
    },
    showSaveDialog: async function (options) {
        return platform.showSaveDialog(options);
    },
    alert: function (message) {
        return platform.alertDialog(message);
    },
    confirm: function (message) {
        return platform.confirmDialog(message);
    }
};

export default dialog;