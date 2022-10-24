package su.sres.signalservice.api.storage;

import com.google.protobuf.ByteString;

import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.util.OptionalUtil;
import su.sres.signalservice.api.util.ProtoUtil;
import su.sres.signalservice.internal.storage.protos.AccountRecord;

import java.util.Objects;

public final class SignalAccountRecord implements SignalRecord {

    private final StorageId     id;
    private final AccountRecord proto;
    private final boolean       hasUnknownFields;

    private final Optional<String> givenName;
    private final Optional<String> familyName;
    private final Optional<String> avatarUrlPath;
    private final Optional<byte[]> profileKey;

    public SignalAccountRecord(StorageId id, AccountRecord proto) {
        this.id               = id;
        this.proto            = proto;
        this.hasUnknownFields = ProtoUtil.hasUnknownFields(proto);

        this.givenName     = OptionalUtil.absentIfEmpty(proto.getGivenName());
        this.familyName    = OptionalUtil.absentIfEmpty(proto.getFamilyName());
        this.profileKey    = OptionalUtil.absentIfEmpty(proto.getProfileKey());
        this.avatarUrlPath = OptionalUtil.absentIfEmpty(proto.getAvatarUrlPath());
    }

    @Override
    public StorageId getId() {
        return id;
    }

    public boolean hasUnknownFields() {
        return hasUnknownFields;
    }

    public byte[] serializeUnknownFields() {
        return hasUnknownFields ? proto.toByteArray() : null;
    }

    public Optional<String> getGivenName() {
        return givenName;
    }

    public Optional<String> getFamilyName() {
        return familyName;
    }

    public Optional<byte[]> getProfileKey() {
        return profileKey;
    }

    public Optional<String> getAvatarUrlPath() {
        return avatarUrlPath;
    }

    public boolean isNoteToSelfArchived() {
        return proto.getNoteToSelfArchived();
    }

    public boolean isReadReceiptsEnabled() {
        return proto.getReadReceipts();
    }

    public boolean isTypingIndicatorsEnabled() {
        return proto.getTypingIndicators();
    }

    public boolean isSealedSenderIndicatorsEnabled() {
        return proto.getSealedSenderIndicators();
    }

    public boolean isLinkPreviewsEnabled() {
        return proto.getLinkPreviews();
    }

    public AccountRecord.UserLoginSharingMode getUserLoginSharingMode() {
        return proto.getUserLoginSharingMode();
    }

    public boolean isUserLoginUnlisted() {
        return proto.getUnlistedUserLogin();
    }

    AccountRecord toProto() {
        return proto;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SignalAccountRecord that = (SignalAccountRecord) o;
        return id.equals(that.id) &&
                proto.equals(that.proto);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, proto);
    }

    public static final class Builder {
        private final StorageId             id;
        private final AccountRecord.Builder builder;

        private byte[] unknownFields;

        public Builder(byte[] rawId) {
            this.id      = StorageId.forAccount(rawId);
            this.builder = AccountRecord.newBuilder();
        }

        public Builder setUnknownFields(byte[] serializedUnknowns) {
            this.unknownFields = serializedUnknowns;
            return this;
        }

        public Builder setGivenName(String givenName) {
            builder.setGivenName(givenName == null ? "" : givenName);
            return this;
        }

        public Builder setFamilyName(String familyName) {
            builder.setFamilyName(familyName == null ? "" : familyName);
            return this;
        }

        public Builder setProfileKey(byte[] profileKey) {
            builder.setProfileKey(profileKey == null ? ByteString.EMPTY : ByteString.copyFrom(profileKey));
            return this;
        }

        public Builder setAvatarUrlPath(String urlPath) {
            builder.setAvatarUrlPath(urlPath == null ? "" : urlPath);
            return this;
        }

        public Builder setNoteToSelfArchived(boolean archived) {
            builder.setNoteToSelfArchived(archived);
            return this;
        }

        public Builder setReadReceiptsEnabled(boolean enabled) {
            builder.setReadReceipts(enabled);
            return this;
        }

        public Builder setTypingIndicatorsEnabled(boolean enabled) {
            builder.setTypingIndicators(enabled);
            return this;
        }

        public Builder setSealedSenderIndicatorsEnabled(boolean enabled) {
            builder.setSealedSenderIndicators(enabled);
            return this;
        }

        public Builder setLinkPreviewsEnabled(boolean enabled) {
            builder.setLinkPreviews(enabled);
            return this;
        }

        public Builder setUserLoginSharingMode(AccountRecord.UserLoginSharingMode mode) {
            builder.setUserLoginSharingMode(mode);
            return this;
        }

        public Builder setUnlistedUserLogin(boolean unlisted) {
            builder.setUnlistedUserLogin(unlisted);
            return this;
        }

        public SignalAccountRecord build() {
            AccountRecord proto = builder.build();

            if (unknownFields != null) {
                proto = ProtoUtil.combineWithUnknownFields(proto, unknownFields);
            }

            return new SignalAccountRecord(id, proto);
        }
    }
}