package io.nekohasekai.sagernet.fmt

import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput

abstract class Serializable : Parcelable {
    abstract fun initializeDefaultValues()
    abstract fun serializeToBuffer(output: ByteBufferOutput)
    abstract fun deserializeFromBuffer(input: ByteBufferInput)

    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeByteArray(KryoConverters.serialize(this))
    }

    abstract class CREATOR<T : Serializable> : Parcelable.Creator<T> {
        abstract fun newInstance(): T

        override fun createFromParcel(source: Parcel): T {
            val instance = newInstance()
            return KryoConverters.deserialize(instance, source.createByteArray())
                ?: throw BadParcelableException("Unable to deserialize ${instance.javaClass.simpleName} from parcel")
        }
    }

}
