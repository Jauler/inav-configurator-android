import platform from './platform';

const store = {
    get: (key, defaultValue) => {
        return platform.storeGet(key, defaultValue);
    },
    set: (key, value) => {
        platform.storeSet(key, value);
    },
    delete: (key) => {
        platform.storeDelete(key);
    }
}   

export default store;