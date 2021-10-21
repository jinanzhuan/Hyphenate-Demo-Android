package io.agora.chatdemo.general.repositories;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.agora.CallBack;
import io.agora.ValueCallBack;
import io.agora.chat.ChatClient;
import io.agora.chat.UserInfo;
import io.agora.chat.uikit.domain.EaseUser;
import io.agora.chat.uikit.manager.EaseThreadManager;
import io.agora.chat.uikit.utils.EaseUtils;
import io.agora.chatdemo.DemoHelper;
import io.agora.chatdemo.general.callbacks.ResultCallBack;
import io.agora.chatdemo.general.db.entity.EmUserEntity;
import io.agora.chatdemo.general.net.ErrorCode;
import io.agora.chatdemo.general.net.Resource;
import io.agora.exceptions.ChatException;
import io.agora.util.EMLog;

public class EMContactManagerRepository extends BaseEMRepository{

    public LiveData<Resource<Boolean>> addContact(String username, String reason) {
        return new NetworkOnlyResource<Boolean>() {

            @Override
            protected void createCall(@NonNull ResultCallBack<LiveData<Boolean>> callBack) {
                if(getCurrentUser().equalsIgnoreCase(username)) {
                    callBack.onError(ErrorCode.ADD_SELF_ERROR);
                    return;
                }
                List<String> users = null;
                if(getUserDao() != null) {
                    users = getUserDao().loadContactUsers();
                }
                if(users != null && users.contains(username)) {
                    if(getContactManager().getBlackListUsernames().contains(username)) {
                        callBack.onError(ErrorCode.FRIEND_BLACK_ERROR);
                        return;
                    }
                    callBack.onError(ErrorCode.FRIEND_ERROR);
                    return;
                }
                getContactManager().asyncAddContact(username, reason, new CallBack() {
                    @Override
                    public void onSuccess() {
                        callBack.onSuccess(new MutableLiveData<>(true));
                    }

                    @Override
                    public void onError(int code, String error) {
                        callBack.onError(code, error);
                    }

                    @Override
                    public void onProgress(int progress, String status) {

                    }
                });
            }

        }.asLiveData();
    }

    public LiveData<Resource<List<EaseUser>>> getContactList(boolean fetchServer) {
        return new NetworkBoundResource<List<EaseUser>, List<EaseUser>>() {

            @Override
            protected boolean shouldFetch(List<EaseUser> data) {
                return fetchServer;
            }

            @Override
            protected LiveData<List<EaseUser>> loadFromDb() {
                return Transformations.map(getUserDao().loadUsers(), result -> {
                    if(result != null) {
                        sortData(result);
                    }
                    return result;
                });
            }

            @Override
            protected void createCall(ResultCallBack<LiveData<List<EaseUser>>> callBack) {
                if(!isLoggedIn()) {
                    callBack.onError(ErrorCode.NOT_LOGIN);
                    return;
                }
                if(!isLoggedIn()) {
                    callBack.onError(ErrorCode.NOT_LOGIN);
                    return;
                }
                runOnIOThread(()-> {
                    try {
                        List<String> usernames = getContactManager().getAllContactsFromServer();
                        List<String> ids = getContactManager().getSelfIdsOnOtherPlatform();
                        if(usernames == null) {
                            usernames = new ArrayList<>();
                        }
                        if(ids != null && !ids.isEmpty()) {
                            usernames.addAll(ids);
                        }
                        callBack.onSuccess(createLiveData(EmUserEntity.parse(usernames)));

                    } catch (ChatException e) {
                        e.printStackTrace();
                        callBack.onError(e.getErrorCode(), e.getDescription());
                    }
                });
            }

            @Override
            protected void saveCallResult(List<EaseUser> items) {
                if(getUserDao() != null) {
                    getUserDao().clearUsers();
                    getUserDao().insert(EmUserEntity.parseList(items));
                }
            }

        }.asLiveData();
    }

    /**
     * 从服务器批量获取用户信息
     */
    private void fetchUserInfoByIds(String[] users, List<String> blackList, List<EaseUser> easeUsers, List<EaseUser> exitUsers, ResultCallBack<LiveData<List<EaseUser>>> callBack, boolean callback){
        ChatClient.getInstance().userInfoManager().fetchUserInfoByUserId(users, new ValueCallBack<Map<String, UserInfo>>() {
            @Override
            public void onSuccess(Map<String, UserInfo> value) {
                List<EaseUser> users = EmUserEntity.parseUserInfo(value);

                if(users != null && !users.isEmpty()) {
                    for (EaseUser user : users) {
                        if(blackList != null && !blackList.isEmpty()) {
                            if(blackList.contains(user.getUsername())) {
                                user.setContact(1);
                            }else{
                                user.setContact(0);
                            }
                        }else{
                            user.setContact(0);
                        }

                        if(DemoHelper.getInstance().isCurrentUserFromOtherDevice(user.getUsername())){
                            UserInfo selfInfo =  value.get(ChatClient.getInstance().getCurrentUser());
                            if(selfInfo != null){
                                user.setNickname(selfInfo.getNickName());
                                user.setAvatar(selfInfo.getAvatarUrl());
                                user.setEmail(selfInfo.getEmail());
                                user.setGender(selfInfo.getGender());
                                user.setBirth(selfInfo.getBirth());
                                user.setSign(selfInfo.getSignature());
                                user.setExt(selfInfo.getExt());
                            }
                        }
                    }
                }
                users.remove(ChatClient.getInstance().getCurrentUser());
                easeUsers.addAll(users);
                if(callback){
                    if(exitUsers != null){
                        easeUsers.addAll(exitUsers);
                    }
                    sortData(easeUsers);
                    callBack.onSuccess(createLiveData(easeUsers));
                }
            }

            @Override
            public void onError(int error, String errorMsg) {
                callBack.onError(error, errorMsg);
                easeUsers.addAll(EmUserEntity.parse(users));
                if(callback){
                    easeUsers.addAll(exitUsers);
                    sortData(easeUsers);
                    callBack.onSuccess(createLiveData(easeUsers));
                }
            }
        });
    }

    /**
     * 获取联系人列表
     * @param callBack
     */
    public void getContactList(ResultCallBack<List<EaseUser>> callBack) {
        if(!isLoggedIn()) {
            callBack.onError(ErrorCode.NOT_LOGIN);
            return;
        }
        runOnIOThread(()-> {
            try {
                List<String> usernames = getContactManager().getAllContactsFromServer();
                List<String> ids = getContactManager().getSelfIdsOnOtherPlatform();
                if(usernames == null) {
                    usernames = new ArrayList<>();
                }
                if(ids != null && !ids.isEmpty()) {
                    usernames.addAll(ids);
                }
                List<EaseUser> easeUsers = EmUserEntity.parse(usernames);
                if(usernames != null && !usernames.isEmpty()) {
                    List<String> blackListFromServer = getContactManager().getBlackListFromServer();
                    for (EaseUser user : easeUsers) {
                        if(blackListFromServer != null && !blackListFromServer.isEmpty()) {
                            if(blackListFromServer.contains(user.getUsername())) {
                                user.setContact(1);
                            }
                        }
                    }
                }
                sortData(easeUsers);
                if(callBack != null) {
                    callBack.onSuccess(easeUsers);
                }
            } catch (ChatException e) {
                e.printStackTrace();
                if(callBack != null) {
                    callBack.onError(e.getErrorCode(), e.getDescription());
                }
            }
        });
    }

    private void sortData(List<EaseUser> data) {
        if(data == null || data.isEmpty()) {
            return;
        }
        Collections.sort(data, new Comparator<EaseUser>() {

            @Override
            public int compare(EaseUser lhs, EaseUser rhs) {
                if(lhs.getInitialLetter().equals(rhs.getInitialLetter())){
                    return lhs.getNickname().compareTo(rhs.getNickname());
                }else{
                    if("#".equals(lhs.getInitialLetter())){
                        return 1;
                    }else if("#".equals(rhs.getInitialLetter())){
                        return -1;
                    }
                    return lhs.getInitialLetter().compareTo(rhs.getInitialLetter());
                }

            }
        });
    }

    /**
     * 获取黑名单
     * @return
     */
    public LiveData<Resource<List<EaseUser>>> getBlackContactList() {
        return new NetworkBoundResource<List<EaseUser>, List<EaseUser>>() {
            @Override
            protected boolean shouldFetch(List<EaseUser> data) {
                return true;
            }

            @Override
            protected LiveData<List<EaseUser>> loadFromDb() {
                return getUserDao().loadBlackUsers();
            }

            @Override
            protected void createCall(ResultCallBack<LiveData<List<EaseUser>>> callBack) {
                if(!isLoggedIn()) {
                    callBack.onError(ErrorCode.NOT_LOGIN);
                    return;
                }
                getContactManager().asyncGetBlackListFromServer(new ValueCallBack<List<String>>() {
                    @Override
                    public void onSuccess(List<String> value) {
                        if(value != null && value.size()> 0) {
                            //回调返回的数据
                            List<EaseUser> easeUsers = new ArrayList<>();
                            int size = value.size();
                            int index = 0;
                            int tagNumber = 100;
                            while (size > 100) {
                                List<String> userList = value.subList(index, index + tagNumber);
                                String[] userArray = new String[userList.size()];
                                userList.toArray(userArray);
                                size -= tagNumber;
                                index += tagNumber;
                                if (size == 0) {
                                    fetchUserInfoByIds(userArray, null, easeUsers, null, callBack, true);
                                } else {
                                    fetchUserInfoByIds(userArray, null, easeUsers, null, callBack, false);
                                    }
                                }
                                if (size > 0) {
                                    List<String> userList = value.subList(index, index + size);
                                    String[] userArray = new String[userList.size()];
                                    userList.toArray(userArray);
                                    fetchUserInfoByIds(userArray, value, easeUsers, null, callBack, true);
                                }
                        }else{
                            EMLog.e("EMContactManagerRepository","getBlackContactList is null");
                            List<EaseUser> users = EmUserEntity.parse(value);
                            callBack.onSuccess(createLiveData(users));
                        }
                    }

                    @Override
                    public void onError(int error, String errorMsg) {
                        callBack.onError(error, errorMsg);
                    }
                });
            }

            @Override
            protected void saveCallResult(List<EaseUser> items) {
                if(getUserDao() != null) {
                    getUserDao().clearBlackUsers();
                    getUserDao().insert(EmUserEntity.parseList(items));
                }
            }

        }.asLiveData();
    }

    /**
     * 获取黑名单用户列表
     * @param callBack
     */
    public void getBlackContactList(ResultCallBack<List<EaseUser>> callBack) {
        if(!isLoggedIn()) {
            callBack.onError(ErrorCode.NOT_LOGIN);
            return;
        }
        getContactManager().asyncGetBlackListFromServer(new ValueCallBack<List<String>>() {
            @Override
            public void onSuccess(List<String> value) {
                List<EaseUser> users = EmUserEntity.parse(value);
                if(users != null && !users.isEmpty()) {
                    for (EaseUser user : users) {
                        user.setContact(1);
                    }
                }
                if(callBack != null) {
                    callBack.onSuccess(users);
                }
            }

            @Override
            public void onError(int error, String errorMsg) {
                if(callBack != null) {
                    callBack.onError(error, errorMsg);
                }
            }
        });
    }

    /**
     * 删除联系人
     * @param username
     * @return
     */
    public LiveData<Resource<Boolean>> deleteContact(String username) {
        return new NetworkOnlyResource<Boolean>() {
            @Override
            protected void createCall(@NonNull ResultCallBack<LiveData<Boolean>> callBack) {
                DemoHelper.getInstance().getModel().deleteUsername(username, true);
                getContactManager().asyncDeleteContact(username, new CallBack() {
                    @Override
                    public void onSuccess() {
                        DemoHelper.getInstance().deleteContact(username);
                        callBack.onSuccess(createLiveData(true));
                    }

                    @Override
                    public void onError(int code, String error) {
                        callBack.onError(code, error);
                    }

                    @Override
                    public void onProgress(int progress, String status) {

                    }
                });
            }
        }.asLiveData();
    }

    /**
     * 添加到黑名单
     * @param username
     * @param both 把用户加入黑民单时，如果是both双方发消息时对方都收不到；如果不是，
     *             则我能给黑名单的中用户发消息，但是对方发给我时我是收不到的
     * @return
     */
    public LiveData<Resource<Boolean>> addUserToBlackList(String username, boolean both) {
        return new NetworkOnlyResource<Boolean>() {
            @Override
            protected void createCall(@NonNull ResultCallBack<LiveData<Boolean>> callBack) {
                getContactManager().asyncAddUserToBlackList(username, both, new CallBack() {
                    @Override
                    public void onSuccess() {
                        int res = getUserDao().updateContact(1,username);
                        callBack.onSuccess(createLiveData(true));
                    }

                    @Override
                    public void onError(int code, String error) {
                        callBack.onError(code, error);
                    }

                    @Override
                    public void onProgress(int progress, String status) {

                    }
                });
            }
        }.asLiveData();
    }

    /**
     * 移出黑名单
     * @param username
     * @return
     */
    public LiveData<Resource<Boolean>> removeUserFromBlackList(String username) {
        return new NetworkOnlyResource<Boolean>() {
            @Override
            protected void createCall(@NonNull ResultCallBack<LiveData<Boolean>> callBack) {
                getContactManager().asyncRemoveUserFromBlackList(username, new CallBack() {
                    @Override
                    public void onSuccess() {
                        int res = getUserDao().updateContact(0,username);
                        callBack.onSuccess(createLiveData(true));
                    }

                    @Override
                    public void onError(int code, String error) {
                        callBack.onError(code, error);
                    }

                    @Override
                    public void onProgress(int progress, String status) {

                    }
                });
            }
        }.asLiveData();
    }

    public LiveData<Resource<List<EaseUser>>> getSearchContacts(String keyword) {
        return new NetworkOnlyResource<List<EaseUser>>() {
            @Override
            protected void createCall(@NonNull ResultCallBack<LiveData<List<EaseUser>>> callBack) {
                EaseThreadManager.getInstance().runOnIOThread(()-> {
                    List<EaseUser> easeUsers = null;
                    if(getUserDao() != null) {
                        easeUsers = getUserDao().loadContacts();
                    }
                    List<EaseUser> list = new ArrayList<>();
                    if(easeUsers != null && !easeUsers.isEmpty()) {
                        for (EaseUser user : easeUsers) {
                            if(user.getUsername().contains(keyword) || (!TextUtils.isEmpty(user.getNickname()) && user.getNickname().contains(keyword))) {
                                list.add(user);
                            }
                        }
                    }
                    if(list != null && list.size() > 1) {
                        sortData(list);
                    }
                    callBack.onSuccess(createLiveData(list));
                });

            }
        }.asLiveData();
    }

    public LiveData<Resource<EaseUser>> getUserInfoById(final String username, boolean mIsFriend) {
        return new NetworkBoundResource<EaseUser, EaseUser>() {
            @Override
            protected boolean shouldFetch(EaseUser data) {
                return true;
            }

            @Override
            protected LiveData<EaseUser> loadFromDb() {
                List<EaseUser> users = getUserDao().loadUserByUserId(username);
                return createLiveData(users.get(0));
            }

            @Override
            protected void createCall(ResultCallBack<LiveData<EaseUser>> callBack) {
                String userId = username;
                if(DemoHelper.getInstance().isCurrentUserFromOtherDevice(username)) {
                    userId = ChatClient.getInstance().getCurrentUser();
                }
                String[] userIds = new String[]{userId};
                String finalUserId = userId;
                ChatClient.getInstance().userInfoManager().fetchUserInfoByUserId(userIds, new ValueCallBack<Map<String, UserInfo>>() {
                    @Override
                    public void onSuccess(Map<String, UserInfo> value) {
                        if(callBack != null) {
                            if(mIsFriend) {
                                callBack.onSuccess(createLiveData(transformEMUserInfo(value.get(finalUserId))));
                            }
                        }
                    }

                    @Override
                    public void onError(int error, String errorMsg) {
                        if(callBack != null) {
                            callBack.onError(error, errorMsg);
                        }
                    }
                });
            }

            @Override
            protected void saveCallResult(EaseUser item) {
                getUserDao().insert(EmUserEntity.parseParent(item));
            }
        }.asLiveData();
    }

    private EaseUser transformEMUserInfo(UserInfo info) {
        if(info != null){
            EaseUser userEntity = new EaseUser();
            userEntity.setUsername(info.getUserId());
            userEntity.setNickname(info.getNickName());
            userEntity.setEmail(info.getEmail());
            userEntity.setAvatar(info.getAvatarUrl());
            userEntity.setBirth(info.getBirth());
            userEntity.setGender(info.getGender());
            userEntity.setExt(info.getExt());
            userEntity.setSign(info.getSignature());
            EaseUtils.setUserInitialLetter(userEntity);
            try {
                userEntity.setContact(getUserDao().getUserContactById(info.getUserId()));
            } catch (Exception e) {
                e.printStackTrace();
            }
            return userEntity;
        }
        return null;
    }
}
